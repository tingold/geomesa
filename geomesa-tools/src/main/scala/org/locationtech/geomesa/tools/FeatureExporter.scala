package org.locationtech.geomesa.tools

import java.io._
import java.text.SimpleDateFormat
import java.util.Date

import com.typesafe.scalalogging.slf4j.Logging
import com.vividsolutions.jts.geom.{Coordinate, Geometry}
import org.apache.commons.lang.StringEscapeUtils
import org.geotools.GML
import org.geotools.GML.Version
import org.geotools.data.DataUtilities
import org.geotools.data.shapefile.{ShapefileDataStore, ShapefileDataStoreFactory}
import org.geotools.data.simple.{SimpleFeatureCollection, SimpleFeatureStore}
import org.geotools.geojson.feature.FeatureJSON
import org.geotools.geometry.jts.JTSFactoryFinder
import org.locationtech.geomesa.tools.Utils.Formats
import org.locationtech.geomesa.utils.geotools.Conversions._
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}

import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.util.Try

trait FeatureExporter {
  def write(features: SimpleFeatureCollection, f: File): Unit = {
    val fos = new FileOutputStream(f)
    try {
      write(features, fos)
    } finally {
      fos.close()
    }
  }

  def write(features: SimpleFeatureCollection, os: OutputStream): Unit
}

class GeoJsonExport extends FeatureExporter {

  val featureJson = new FeatureJSON()

  override def write(features: SimpleFeatureCollection, os: OutputStream) {
    featureJson.writeFeatureCollection(features, os)
  }
}

class GmlExport extends FeatureExporter {

  val encode = new GML(Version.WFS1_0)
  encode.setNamespace("location", "location.xsd")

  override def write(features: SimpleFeatureCollection, os: OutputStream) = encode.encode(os, features)
}

class ShapefileExport extends FeatureExporter {

  override def write(features: SimpleFeatureCollection, file: File) = {
    // create a new shapfile data store
    val url = DataUtilities.fileToURL(file)
    val factory = new ShapefileDataStoreFactory()
    val newShapeFile = factory.createDataStore(url).asInstanceOf[ShapefileDataStore]

    // create a schema in the new datastore
    newShapeFile.createSchema(features.getSchema)
    val store = newShapeFile.getFeatureSource.asInstanceOf[SimpleFeatureStore]
    store.addFeatures(features)
  }

  override def write(features: SimpleFeatureCollection, os: OutputStream) =
    throw new UnsupportedOperationException("Cannot export shape file to output stream...use file-based export only")

}

class DelimitedExport(format: String,
                      attributes: Option[String],
                      idAttribute: Option[String],
                      latAttribute: Option[String],
                      lonAttribute: Option[String],
                      dtgAttribute: Option[String]) extends FeatureExporter with Logging {

  val delimiter = 
   format match {
     case Formats.CSV => ","
     case Formats.TSV => "\t"  
   }
  lazy val dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
  lazy val geometryFactory = JTSFactoryFinder.getGeometryFactory

  override def write(features: SimpleFeatureCollection, output: OutputStream) = {

    val attrArr   = attributes.map(_.split("""(?<!\\),""")).getOrElse(Array.empty[String])
    val idAttrArr = List(idAttribute).flatten

    val attributeTypes = idAttrArr ++ attrArr
    val idField = attributeTypes.map(_.split(":")(0).split("=").head.trim).headOption

    val attrNames =
      if (attributeTypes.nonEmpty) {
        attributeTypes
      } else {
        features.getSchema.getAttributeDescriptors.map(_.getLocalName)
      }

    val writer = new PrintWriter(new BufferedWriter(new OutputStreamWriter(output)))

    // write out a header line
    writer.println(attrNames.mkString(delimiter))

    var count = 0
    features.features.foreach { sf =>
      writeFeature(sf, writer, attrNames, idField)
      count = count + 1
      if (count % 10000 == 0) logger.debug("wrote {} features", "" + count)
    }
    logger.info(s"Successfully wrote $count features to output stream")
  }

  val getGeom: SimpleFeature => Option[Geometry] =
    (sf: SimpleFeature) =>
      latAttribute match {
        case None => None
        case Some(attr) =>
          val lat = sf.getAttribute(latAttribute.get).toString.toDouble
          val lon = sf.getAttribute(lonAttribute.get).toString.toDouble
          Some(geometryFactory.createPoint(new Coordinate(lon, lat)))
      }

  val getDate: SimpleFeature => Option[Date] =
    (sf: SimpleFeature) =>
      dtgAttribute match {
        case None => None
        case Some(attr) =>
          val date = sf.getAttribute(attr)
          if (date.isInstanceOf[Date]) {
            Some(date.asInstanceOf[Date])
          } else {
            Some(dateFormat.parse(date.toString))
          }
      }

  def writeFeature(sf: SimpleFeature, writer: PrintWriter, attrNames: Seq[String], idField: Option[String]) = {
    val attrMap = mutable.Map.empty[String, Object]

    // copy attributes into map where we can manipulate them
    attrNames.foreach(a => Try(attrMap.put(a, sf.getAttribute(a))))

    // check that ID is set in the map
    if (idField.nonEmpty && attrMap.getOrElse(idField.get, "").toString.isEmpty) attrMap.put(idField.get, sf.getID)

    // update geom and date as needed
    getGeom(sf).map { geom => attrMap("*geom") = geom }
    getDate(sf).map { date => attrMap("dtg") = date }

    // put the values into a checked list
    val attributeValues = attrNames.map { a =>
      val value = attrMap.getOrElse(a, null)
      if (value == null) {
        ""
      } else if (value.isInstanceOf[java.util.Date]) {
        dateFormat.format(value.asInstanceOf[java.util.Date])
      } else {
        StringEscapeUtils.escapeCsv(value.toString)
      }
    }

    writer.println(attributeValues.mkString(delimiter))
  }

}