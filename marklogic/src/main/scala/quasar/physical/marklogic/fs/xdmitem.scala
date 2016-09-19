/*
 * Copyright 2014–2016 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.physical.marklogic.fs

import quasar.Predef._
import quasar.Data
import quasar.physical.marklogic.xml
import quasar.physical.marklogic.xml.SecureXML

import scala.collection.JavaConverters._
import scala.util.{Success, Failure}
import scala.xml.Elem

import com.marklogic.xcc.types._
import org.threeten.bp._
import scalaz._, Scalaz._

object xdmitem {
  def toData[F[_]: MonadErrMsgs](xdm: XdmItem): F[Data] = xdm match {
    case item: CtsBox                   =>
      Data.singletonObj("cts:box", Data.Obj(ListMap(
        "east"  -> Data.Str(item.getEast),
        "north" -> Data.Str(item.getNorth),
        "south" -> Data.Str(item.getSouth),
        "west"  -> Data.Str(item.getWest)
      ))).point[F]

    case item: CtsCircle                =>
      toData[F](item.getCenter) map { center =>
        Data.singletonObj("cts:circle", Data.Obj(ListMap(
           "center" -> center,
           "radius" -> Data.Str(item.getRadius)
         )))
      }

    case item: CtsPoint                 =>
      Data.singletonObj("cts:point", Data.Obj(ListMap(
        "latitude"  -> Data.Str(item.getLatitude),
        "longitude" -> Data.Str(item.getLongitude)
      ))).point[F]

    case item: CtsPolygon               =>
      item.getVertices.asScala.toList traverse toData[F] map { verts =>
        Data.singletonObj("cts:polygon", Data.singletonObj("vertices", Data.Arr(verts)))
      }

    // TODO: What is the difference between JS{Array, Object} and their *Node variants?
    case item: JSArray                  => jsonToData[F](item.asString)
    case item: JSObject                 => jsonToData[F](item.asString)
    case item: JsonItem                 => jsonToData[F](item.asString)

    case item: XdmAttribute             =>
      val attr = item.asW3cAttr
      Data.singletonObj(attr.getName, Data.Str(attr.getValue)).point[F]

    // TODO: Inefficient for large data as it must be buffered into memory
    case item: XdmBinary                => bytesToData[F](item.asBinaryData)
    case item: XdmComment               => Data.singletonObj("xdm:comment"  , Data.Str(item.asString)).point[F]
    case item: XdmDocument              => xmlToData[F](item.asString)
    case item: XdmElement               => xmlToData[F](item.asString)
    case item: XdmProcessingInstruction => Data.singletonObj("xdm:processingInstruction", Data.Str(item.asString)).point[F]
    case item: XdmText                  => Data._str(item.asString).point[F]
    case item: XSAnyURI                 => Data._str(item.asString).point[F]
    case item: XSBase64Binary           => bytesToData[F](item.asBinaryData)
    case item: XSBoolean                => Data._bool(item.asPrimitiveBoolean).point[F]
    case item: XSDate                   => Data.singletonObj("xs:date"      , Data.Str(item.asString)).point[F]
    case item: XSDateTime               => Data._timestamp(Instant.ofEpochMilli(item.asDate.getTime)).point[F]
    case item: XSDecimal                => Data._dec(item.asBigDecimal).point[F]
    case item: XSDouble                 => Data._dec(item.asBigDecimal).point[F]
    case item: XSDuration               => Data.singletonObj("xs:duration"  , Data.Str(item.asString)).point[F]
    case item: XSFloat                  => Data._dec(item.asBigDecimal).point[F]
    case item: XSGDay                   => Data.singletonObj("xs:gDay"      , Data.Str(item.asString)).point[F]
    case item: XSGMonth                 => Data.singletonObj("xs:gMonth"    , Data.Str(item.asString)).point[F]
    case item: XSGMonthDay              => Data.singletonObj("xs:gMonthDay" , Data.Str(item.asString)).point[F]
    case item: XSGYear                  => Data.singletonObj("xs:gYear"     , Data.Str(item.asString)).point[F]
    case item: XSGYearMonth             => Data.singletonObj("xs:gYearMonth", Data.Str(item.asString)).point[F]
    case item: XSHexBinary              => bytesToData[F](item.asBinaryData)
    case item: XSInteger                => Data._int(item.asBigInteger).point[F]
    case item: XSQName                  => Data._str(item.asString).point[F]
    case item: XSString                 => Data._str(item.asString).point[F]
                                           // NB: This can be represented with org.threeten.bp.OffsetTime
    case item: XSTime                   => Data.singletonObj("xs:time"      , Data.Str(item.asString)).point[F]
    case item: XSUntypedAtomic          => Data._str(item.asString).point[F]
    case other                          => s"No Data representation for '$other'.".wrapNel.raiseError[F, Data]
  }

  ////

  private def bytesToData[F[_]: Applicative](bytes: Array[Byte]): F[Data] =
    Data._binary(ImmutableArray.fromArray(bytes)).point[F]

  private def jsonToData[F[_]: MonadErrMsgs](jsonString: String): F[Data] =
    data.JsonParser.parseFromString(jsonString) match {
      case Success(d) => d.point[F]
      case Failure(e) => e.toString.wrapNel.raiseError[F, Data]
    }

  private def xmlToData[F[_]: MonadErrMsgs](xmlString: String): F[Data] = {
    val el = SecureXML.loadString(xmlString).fold(_.toString.wrapNel.raiseError[F, Elem], _.point[F])

    el flatMap { e =>
      if (xml.qualifiedName(e) === xml.namespaces.ejsonEjson.shows)
        data.fromXml[F](e)
      else
        xml.toData(e).point[F]
    }
  }
}
