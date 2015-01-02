package com.socrata.tileserver
package services

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse.{SC_BAD_REQUEST => ScBadRequest}
import javax.servlet.http.HttpServletResponse.{SC_OK => ScOk}
import scala.util.control.NoStackTrace

import com.rojoma.json.v3.interpolation._
import com.socrata.http.server.util.RequestId.{RequestId, ReqIdHeader}
import org.mockito.Matchers.{anyInt, anyObject}
import org.mockito.Mockito.{verify, when}
import org.scalatest.mock.MockitoSugar

import com.socrata.backend.client.CoreServerClient
import com.socrata.backend.config.CoreServerClientConfig
import com.socrata.http.client.{RequestBuilder, Response, SimpleHttpRequest}
import com.socrata.http.server.HttpRequest.AugmentedHttpServletRequest
import com.socrata.http.server.routing.TypedPathComponent
import com.socrata.http.server.{HttpRequest, HttpResponse}
import com.socrata.thirdparty.curator.ServerProvider

import util.QuadTile

class TileServiceTest extends TestBase with MockitoSugar {
  val EchoMapper = new util.CoordinateMapper(0) {
    override def tilePx(lon: Double, lat:Double): (Int, Int) =
      (lon.toInt, lat.toInt)
  }

  val NonConfig = new CoreServerClientConfig {
    def connectTimeoutSec: Int = 0
    def maxRetries: Int = 0
  }

  val nothingCallback: Response => HttpResponse = r => mock[HttpResponse]

  test("Service supports at least .pbf, .bpbf and .json") {
    val svc = TileService(mock[CoreServerClient])

    svc.types must contain ("pbf")
    svc.types must contain ("bpbf")
    svc.types must contain ("json")
  }

  test("Headers and parameters are correct when making a geo-json query") {
    forAll { (reqId: RequestId,
              id: String,
              param: (String, String),
              header: (String, String)) =>
      val base = RequestBuilder("mock.socrata.com")
      val request = mocks.StaticRequest(param, header)

      val expected = base.
        path(Seq("id", s"$id.geojson")).
        addHeader(ReqIdHeader -> reqId).
        addHeader(header).
        query(Map(param)).
        get.builder

      val client = new CoreServerClient(mock[ServerProvider], NonConfig) {
        override def execute[T](request: RequestBuilder => SimpleHttpRequest,
                                callback: Response => T): T = {
          val actual = request(base).builder
          // The assertions are here, because of weird inversion of control.
          actual.url must equal (expected.url)
          actual.method must equal (expected.method)
          actual.query.toSet must equal (expected.query.toSet)
          actual.headers.toSet must equal (expected.headers.toSet)

          callback(mock[Response])
        }
      }

      TileService(client).geoJsonQuery(reqId,
                                       request,
                                       id,
                                       Map(param),
                                       nothingCallback): Unit
    }
  }

  test("Handle request returns OK when underlying succeeds") {
    forAll { pt: (Int, Int) =>
      val jsonResp = mocks.SeqResponse(Seq(fJson(pt)))
      val client = new CoreServerClient(mock[ServerProvider], NonConfig) {
        override def execute[T](request: RequestBuilder => SimpleHttpRequest,
                                callback: Response => T): T = {
          callback(jsonResp)
        }
      }

      val outputStream = new mocks.ByteArrayServletOutputStream
      val resp = outputStream.responseFor

      TileService(client).handleRequest(mocks.StaticRequest(),
                                        "dataset id",
                                        "point column",
                                        mock[QuadTile],
                                        "json")(resp)

      verify(resp).setStatus(ScOk)

      outputStream.getString must include (jsonResp.toString)
    }
  }

  test("Handle request proxies when underlying returns anything but OK") {
    forAll { (statusCode: Int, payload: String) =>
      val client = new CoreServerClient(mock[ServerProvider], NonConfig) {
        override def execute[T](request: RequestBuilder => SimpleHttpRequest,
                                callback: Response => T): T = {
          val resp = mock[Response]
          when(resp.resultCode).thenReturn(statusCode)
          when(resp.inputStream(anyInt())).
            thenReturn(mocks.StringInputStream(s"""{message: ${encode(payload)}}"""))

          callback(resp)
        }
      }

      val outputStream = new mocks.ByteArrayServletOutputStream
      val resp = outputStream.responseFor

      TileService(client).handleRequest(mocks.StaticRequest(),
                                        "dataset id",
                                        "point column",
                                        mock[QuadTile],
                                        "json")(resp)

      verify(resp).setStatus(statusCode)

      outputStream.getLowStr must include ("underlying")
      outputStream.getString must include (encode(payload))
    }
  }

  test("Handle request returns 'bad request' if processing throws") {
    forAll { message: String =>
      val client = new CoreServerClient(mock[ServerProvider], NonConfig) {
        override def execute[T](request: RequestBuilder => SimpleHttpRequest,
                                callback: Response => T): T = {
          throw new RuntimeException(message)
        }
      }

      val outputStream = new mocks.ByteArrayServletOutputStream
      val resp = outputStream.responseFor

      TileService(client).handleRequest(mocks.StaticRequest(),
                                        "dataset id",
                                        "point column",
                                        mock[QuadTile],
                                        "json")(resp)

      verify(resp).setStatus(ScBadRequest)

      outputStream.getLowStr must include ("unknown error")
      outputStream.getString must include (encode(message))
    }
  }

  test("Get returns success when underlying succeeds") {
    forAll { pt: (Int, Int) =>
      val jsonResp = mocks.SeqResponse(Seq(fJson(pt)))
      val client = new CoreServerClient(mock[ServerProvider], NonConfig) {
        override def execute[T](request: RequestBuilder => SimpleHttpRequest,
                                callback: Response => T): T = {
          callback(jsonResp)
        }
      }

      val outputStream = new mocks.ByteArrayServletOutputStream
      val resp = outputStream.responseFor

      TileService(client).
        service("dataset id",
                "point column",
                0,
                0,
                TypedPathComponent(0, "json")).
        get(mocks.StaticRequest())(resp)

      verify(resp).setStatus(ScOk)

      outputStream.getString must include (jsonResp.toString)
    }
  }

  test("Get returns 'bad request' when given an invalid file extension") {
    val client = new CoreServerClient(mock[ServerProvider], NonConfig) {
      override def execute[T](request: RequestBuilder => SimpleHttpRequest,
                              callback: Response => T): T = {
        callback(mock[Response])
      }
    }

    val outputStream = new mocks.ByteArrayServletOutputStream
    val resp = outputStream.responseFor

    TileService(client).
      service("dataset id",
              "point column",
              0,
              1,
              TypedPathComponent(2, "invalid extension")).
      get(mocks.StaticRequest())(resp)

    verify(resp).setStatus(ScBadRequest)

    outputStream.getLowStr must include ("invalid file type")
  }

  test("Proxied response must include status code, content-type, and payload") {
    forAll { (statusCode: Int, payload: String) =>
      val outputStream = new mocks.ByteArrayServletOutputStream
      val resp = outputStream.responseFor
      val upstream = mocks.StringResponse(json"""{payload: $payload}""".toString,
                                          statusCode)

      TileService.proxyResponse(upstream)(resp)

      verify(resp).setStatus(statusCode)
      verify(resp).setContentType("application/json; charset=UTF-8")

      outputStream.getLowStr must include ("underlying")
      outputStream.getString must include (statusCode.toString)
      outputStream.getString must include (encode(payload))
    }
  }

  test("Bad request must include message and cause") {
    forAll { (message: String, causeMessage: String) =>
      val outputStream = new mocks.ByteArrayServletOutputStream
      val resp = outputStream.responseFor
      val cause = new NoStackTrace {
        override def getMessage: String = causeMessage
      }

      TileService.badRequest(message, cause)(resp)

      verify(resp).setStatus(ScBadRequest)
      verify(resp).setContentType("application/json; charset=UTF-8")

      outputStream.getLowStr must include ("message")
      outputStream.getString must include (encode(message))
      outputStream.getLowStr must include ("cause")
      outputStream.getString must include (encode(causeMessage))
    }
  }

  test("Bad request must include message and info") {
    forAll { (message: String, info: String) =>
      val outputStream = new mocks.ByteArrayServletOutputStream
      val resp = outputStream.responseFor

      TileService.badRequest(message, info)(resp)

      verify(resp).setStatus(ScBadRequest)
      verify(resp).setContentType("application/json; charset=UTF-8")

      outputStream.getLowStr must include ("message")
      outputStream.getString must include (encode(message))
      outputStream.getLowStr must include ("info")
      outputStream.getString must include (encode(info))
    }
  }


  test("Correct request id is extracted") {
    forAll { (reqId: String) =>
      val req = mock[HttpRequest]
      val servReq = mock[HttpServletRequest]
      when(req.servletRequest).thenReturn(new AugmentedHttpServletRequest(servReq))
      when(servReq.getHeader(ReqIdHeader)).thenReturn(reqId)

      TileService.extractRequestId(req) must equal (reqId)
    }
  }

  test("Augmenting parameters adds to where and select") {
    val otherKey = "$other"
    val whereKey = "$where"
    val selectKey = "$select"

    forAll { (rawOtherValue: String,
              rawWhereBase: String,
              rawWhereValue: String,
              rawSelectBase: String,
              rawSelectValue: String) =>
      val otherValue = encode(rawOtherValue) filter (_.isLetterOrDigit)
      val whereBase = encode(rawWhereBase) filter (_.isLetterOrDigit)
      val whereValue = encode(rawWhereValue) filter (_.isLetterOrDigit)
      val selectBase = encode(rawSelectBase) filter (_.isLetterOrDigit)
      val selectValue = encode(rawSelectValue) filter (_.isLetterOrDigit)

      val neither = mocks.StaticRequest(otherKey -> otherValue)
      val where = mocks.StaticRequest(whereKey -> whereBase)
      val select = mocks.StaticRequest(selectKey -> selectBase)

      neither.queryParameters must have size (1)
      val nParams = TileService.augmentParams(neither,
                                              whereValue,
                                              selectValue)
      nParams must have size (3)
      nParams(otherKey) must equal (otherValue)
      nParams(whereKey) must equal (whereValue)
      nParams(selectKey) must equal (selectValue)

      val wParams = TileService.augmentParams(neither ++ where,
                                              whereValue,
                                              selectValue)
      wParams must have size (3)
      wParams(otherKey) must equal (otherValue)

      wParams(whereKey) must startWith (whereBase)
      wParams(whereKey) must endWith (whereValue)
      wParams(whereKey) must include regex ("\\s+and\\s+")

      wParams(selectKey) must equal (selectValue)

      val sParams = TileService.augmentParams(neither ++ select,
                                              whereValue,
                                              selectValue)
      sParams must have size (3)
      sParams(otherKey) must equal (otherValue)
      sParams(whereKey) must equal (whereValue)

      sParams(selectKey) must startWith (selectBase)
      sParams(selectKey) must endWith (selectValue)
      sParams(selectKey) must include regex (",\\s*")

      val wsParams = TileService.augmentParams(neither ++ where ++ select,
                                               whereValue,
                                               selectValue)
      sParams must have size (3)
      sParams(otherKey) must equal (otherValue)

      wParams(whereKey) must startWith (whereBase)
      wParams(whereKey) must endWith (whereValue)
      wParams(whereKey) must include regex ("\\s+and\\s+")

      sParams(selectKey) must startWith (selectBase)
      sParams(selectKey) must endWith (selectValue)
      sParams(selectKey) must include regex (",\\s*")
    }
  }

  test("An empty list of coordinates rolls up correctly") {
    val noFeatures = Seq.empty
    TileService.rollup(EchoMapper, noFeatures) must be (Set.empty)
  }

  test("A single coordinate rolls up correctly") {
    forAll { pt: (Int, Int) =>
      TileService.rollup(EchoMapper, Seq(fJson(pt))) must equal (Set(feature(pt)))
    }
  }

  test("Unique coordinates are included when rolled up") {
    forAll { (pt0: (Int, Int), pt1: (Int, Int), pt2: (Int, Int)) =>
      whenever (uniq(pt0, pt1, pt2)) {
        val coordinates = Seq(fJson(pt0),
                              fJson(pt1),
                              fJson(pt2))
        val expected = Set(feature(pt0),
                           feature(pt1),
                           feature(pt2))
        val actual = TileService.rollup(EchoMapper, coordinates)

        actual must equal (expected)
      }
    }
  }

  test("Coordinates have correct counts when rolled up") {
    forAll { (pt0: (Int, Int), pt1: (Int, Int), pt2: (Int, Int)) =>
      whenever (uniq(pt0, pt1, pt2)) {
        val coordinates = Seq(fJson(pt0),
                              fJson(pt1),
                              fJson(pt1),
                              fJson(pt2),
                              fJson(pt2))
        val expected = Set(feature(pt0, count=1),
                           feature(pt1, count=2),
                           feature(pt2, count=2))
        val actual = TileService.rollup(EchoMapper, coordinates)

        actual must equal (expected)
      }
    }
  }

  test("Coordinates with unique properties are not rolled up") {
    forAll { (pt0: (Int, Int),
              pt1: (Int, Int),
              prop0: (String, String),
              prop1: (String, String)) =>
      val (k0, _) = prop0
      val (k1, _) = prop1

      whenever (pt0 != pt1 && prop0 != prop1 && k0 != k1) {
        val coordinates = Seq(fJson(pt0, Map(prop0)),
                              fJson(pt0, Map(prop0, prop1)),
                              fJson(pt0, Map(prop1)),
                              fJson(pt1, Map(prop1)),
                              fJson(pt1, Map(prop1)))
        val expected = Set(feature(pt0, 1, Map(prop0)),
                           feature(pt0, 1, Map(prop0, prop1)),
                           feature(pt0, 1, Map(prop1)),
                           feature(pt1, 2, Map(prop1)))

        val actual = TileService.rollup(EchoMapper, coordinates)

        actual must equal (expected)
      }
    }
  }

  test("Encoder includes all features") {
    forAll { (pt0: (Int, Int),
              pt1: (Int, Int),
              pt2: (Int, Int),
              prop0: (String, String),
              prop1: (String, String)) =>
      val (k0, _) = prop0
      val (k1, _) = prop1

      whenever (uniq(pt0, pt1, pt2) && prop0 != prop1 && k0 != k1) {
        val coordinates = Seq(fJson(pt0),
                              fJson(pt1),
                              fJson(pt2))
        val expected = Set(feature(pt0),
                           feature(pt1),
                           feature(pt2))

        val bytes = TileService.
          encoder(EchoMapper, mocks.StringEncoder())(mocks.SeqResponse(coordinates))
        bytes must be ('defined)
        bytes.get.length must be > 0
        new String(bytes.get, "UTF-8") must equal (mocks.StringEncoder.encFeatures(expected))
      }
    }
  }
}
