package leochat.model

import java.io.ByteArrayInputStream

import scala.collection.JavaConversions._
import scala.io.Source
import scala.util.control.NonFatal

import com.amazonaws.{AmazonClientException, AmazonServiceException}
import com.amazonaws.{ClientConfiguration, Protocol}
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.{Bucket, GetObjectRequest, ListObjectsRequest, ObjectMetadata, PutObjectRequest}

import com.typesafe.config.{Config, ConfigFactory}

import xitrum.Logger

object LeoFS extends Logger {
  private val leofsConfig = xitrum.Config.application.getConfig("leofs")

  private val s3 = {
    // You need to set 'Proxy host', 'Proxy port' and 'Protocol'
    val config = new ClientConfiguration
    config.withProtocol(Protocol.HTTP)
    config.setProxyHost(leofsConfig.getString("proxyhost"))
    config.setProxyPort(leofsConfig.getInt("proxyport"))

    val accessKeyId     = leofsConfig.getString("accessKeyId")
    val secretAccessKey = leofsConfig.getString("secretAccessKey")

    val credentials = new BasicAWSCredentials(accessKeyId, secretAccessKey)
    new AmazonS3Client(credentials, config)
  }

  private def lookupBucket(name: String): Option[Bucket] = {
    val buckets = s3.listBuckets()
    buckets.toList.find { b => b.getName == name }
  }

  private var bucket: Bucket = {
    lookupBucket(leofsConfig.getString("bucket")).getOrElse {
      try {
        s3.createBucket(leofsConfig.getString("bucket"))
      } catch {
        case NonFatal(e) =>
          logger.error("Could not create bucket", e)
          throw e
          null
      }
    }
  }

  private def stringToStream(data: String) = {
    new ByteArrayInputStream(data.getBytes("UTF-8"))
  }

  def save(data: String) {
    // s3.listObjects() they are returned in alphabetical order (string comparison!)
    val length = Long.MaxValue.toString.length
    val key    = s"%${length}d".format(Long.MaxValue - System.currentTimeMillis())

    val meta        = new ObjectMetadata
    val customeMeta = Map("xitrum_content_type" -> "image")
    meta.setUserMetadata(customeMeta)
    meta.setContentLength(data.length)
    try {
      s3.putObject(new PutObjectRequest(bucket.getName, key, stringToStream(data), meta))
    } catch {
      case NonFatal(e) =>
        logger.warn("save error: " + e)
    }
  }

  private def read(key: String): Option[String] = {
    try {
      val v = s3.getObject(new GetObjectRequest(bucket.getName, key))
      val content = v.getObjectContent()
      Some(Source.fromInputStream(content).getLines.mkString(""))
    } catch {
      case NonFatal(e) =>
        logger.warn("read error: " + e)
        None
    }
  }

  def readHead(num: Int): Seq[String] = {
    try {
      val r = new ListObjectsRequest
      r.setBucketName(bucket.getName)
      r.setMaxKeys(num)

      var messages = Seq[String]()
      val objectListing = s3.listObjects(r)
      objectListing.getObjectSummaries.toList.foreach { meta =>
        // Display older to newer
        val msg = read(meta.getKey).getOrElse("")
        messages = msg +: messages
      }
      messages
    } catch {
      case NonFatal(e) =>
        logger.warn("readHead error: " + e)
        Seq()
    }
  }
}