package com.socrata.tileserver
package services

import com.rojoma.json.v3.ast.JValue
import com.rojoma.json.v3.codec.JsonDecode.fromJValue
import com.rojoma.json.v3.codec.JsonEncode.toJValue
import com.rojoma.json.v3.conversions._
import com.rojoma.simplearm.v2.{Managed, ResourceScope}
import com.socrata.http.client.Response.ContentP
import com.socrata.http.client.{HttpClient, RequestBuilder, Response}
import com.socrata.http.server.implicits._
import com.socrata.http.server.responses._
import com.socrata.http.server.routing.{SimpleResource, TypedPathComponent}
import com.socrata.http.server.util.RequestId.{RequestId, ReqIdHeader, getFromRequest}
import com.socrata.http.server.{HttpRequest, HttpResponse}
import com.socrata.thirdparty.geojson.{GeoJson, FeatureCollectionJson}
import com.vividsolutions.jts.geom.GeometryFactory
import java.net.URLDecoder
import no.ecc.vectortile.{VectorTileDecoder, VectorTileEncoder}
import org.slf4j.LoggerFactory
import scala.util.{Try, Success, Failure}
import util.{CoordinateMapper, ExcludedHeaders, Extensions, InvalidRequest, QuadTile}

case class ImageQueryService(http: HttpClient) extends SimpleResource {
  private val geomFactory = new GeometryFactory()
  private val logger = LoggerFactory.getLogger(getClass)

  def badRequest(message: String, cause: Throwable): HttpResponse = {
    logger.warn(message, cause)

    BadRequest ~>
      Header("Access-Control-Allow-Origin", "*") ~>
      Content("application/json",
              s"""{"message": "$message", "cause": "${cause.getMessage}"}""")
  }

  def badRequest(message: String, info: String): HttpResponse = {
    logger.warn(s"$message: $info")

    BadRequest ~>
      Header("Access-Control-Allow-Origin", "*") ~>
      Content("application/json",
              s"""{"message": "$message", "info": "$info"}""")
  }

  val types: Set[String] = Extensions.keySet

  def extractRequestId(req: HttpRequest): RequestId =
    getFromRequest(req.servletRequest)

  def extractHost(req: HttpRequest): Try[(String, Option[Int])] = {
    req.header("X-Socrata-Host") match {
      case Some(h) =>
        h.split(':') match {
          case Array(host, port) => Try { (host, Some(port.toInt)) }
          case Array(host) => Success ( (host, None) )
          case _ => Failure(InvalidRequest("Invalid X-Socrata-Host header", h))
        }
      case None => Failure(InvalidRequest("Invalid X-Socrata-Host header",
                                          "Missing header"))
    }
  }

  def geoJsonQuery(hostDef: (String, Option[Int]),
                   requestId: RequestId,
                   req: HttpRequest,
                   id: String,
                   params: Map[String, String]): (String, Response) = {
    val rs = req.resourceScope
    val (host, maybePort) = hostDef

    val headerNames = req.headerNames filterNot { s: String =>
      ExcludedHeaders(s.toLowerCase)
    }

    val headers =
      headerNames flatMap { name: String =>
        req.headers(name) map { (name, _) }
      } toIterable

    val builder = RequestBuilder(host).
      path(Seq("api", "id", s"$id.geojson")).
      addHeaders(headers).
      addHeader(ReqIdHeader -> requestId).
      query(params)

    val jsonReq = maybePort match {
      case Some(port) => builder.port(port).get
      case None => builder.get
    }

    (URLDecoder.decode(jsonReq.toString, "UTF-8"), http.execute(jsonReq, rs))
  }

  def encoder(mapper: CoordinateMapper): Response => Option[Array[Byte]] = resp => {
    val encoder: VectorTileEncoder = new VectorTileEncoder(4096)
    val jsonp: ContentP = _ map { t =>
      t.getBaseType.startsWith("application/") && t.getBaseType.endsWith("json")
    } getOrElse false

    GeoJson.codec.decode(resp.jValue(jsonp).toV2) collect {
      case FeatureCollectionJson(features, _) => {
        val coords = features map { f =>
          (f.geometry.getCoordinate, f.properties)
        }

        val pixels = coords map { case (coord, props) =>
          (mapper.px(coord), props)
        }

        val points = pixels groupBy { case (px, props) =>
          (geomFactory.createPoint(px), props)
        }

        val rollups = points map {
          case (k, v) => (k, v.size)
        }

        rollups foreach { case ((pt, jprops), count) =>
          val props = jprops map { case (k, v) =>
            (k, fromJValue[String](v.toV3))
          }

          val attrs = new java.util.HashMap[String, JValue]
          attrs.put("count", toJValue(count))
          attrs.put("properties", toJValue(props))
          encoder.addFeature("main", attrs, pt)
        }

        encoder.encode()
      }
    }
  }

  def augmentParams(req: HttpRequest,
                    where: String,
                    select: String): Map[String, String] = {
    val params = req.queryParameters
    val whereParam = if (params.contains("$where"))
      params("$where") + s"and $where" else where

    val selectParam = if (params.contains("$select"))
      params("$select") + s", $select" else select

    params + ("$where" -> whereParam) + ("$select" -> selectParam)
  }

  def handleLayer(req: HttpRequest,
                  identifier: String,
                  pointColumn: String,
                  tile: QuadTile,
                  ext: String): HttpResponse = {
    val mapper = tile.mapper
    val withinBox = tile.withinBox(pointColumn)

    val resp = extractHost(req) map { hostDef =>
      val params = augmentParams(req, withinBox, pointColumn)
      val requestId = extractRequestId(req)
      logger.info(s"$ReqIdHeader: $requestId")

      val (jsonReq, resp) = geoJsonQuery(hostDef,
                                         requestId,
                                         req,
                                         identifier,
                                         params)
      resp.resultCode match {
        case 200 => {
          logger.info(s"Success! ${jsonReq}")
          Extensions(ext)(encoder(mapper), resp)
        }
        case _ => badRequest("Underlying request failed", jsonReq)
      }
    }

    resp match {
      case Success(s) => s
      case Failure(InvalidRequest(message, info)) =>
        badRequest(message, info)
      case Failure(e) => {
        badRequest("Unknown error", e)
      }
    }
  }

  def service(identifier: String,
              pointColumn: String,
              z: Int,
              x: Int,
              typedY: TypedPathComponent[Int]) =
    new SimpleResource {
      val TypedPathComponent(y, ext) = typedY

      override def get = if (types(ext))
        req => handleLayer(req, identifier, pointColumn, QuadTile(x, y, z), ext)
      else
        req => badRequest("Invalid file type", ext)
    }
}
