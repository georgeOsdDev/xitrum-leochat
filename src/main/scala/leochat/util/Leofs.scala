package leochat.util

import java.io.{BufferedReader, ByteArrayInputStream, File, FileOutputStream, InputStreamReader, OutputStreamWriter}
import java.util.UUID
import javax.xml.bind.DatatypeConverter

import scala.collection.JavaConversions._
import scala.util.{Try, Success, Failure}

import com.amazonaws.AmazonClientException
import com.amazonaws.AmazonServiceException
import com.amazonaws.{ClientConfiguration, Protocol}
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.{Bucket, GetObjectRequest, ListObjectsRequest, ObjectMetadata, PutObjectRequest}

import com.typesafe.config.{Config, ConfigFactory}

object LeoFS{

  val leofsConfig = xitrum.Config.application.getConfig("leofs")

    /* ---------------------------------------------------------
   * You need to set 'Proxy host', 'Proxy port' and 'Protocol'
   * --------------------------------------------------------- */
  val config = new ClientConfiguration();
    config.withProtocol(Protocol.HTTP)
    config.setProxyHost(leofsConfig.getString("proxyhost"))
    config.setProxyPort(leofsConfig.getInt("proxyport"))

  val accessKeyId = leofsConfig.getString("accessKeyId")
  val secretAccessKey = leofsConfig.getString("secretAccessKey")

  val credentials = new BasicAWSCredentials(accessKeyId, secretAccessKey)
  val s3 = new AmazonS3Client(credentials, config);

  var bucket:Bucket = _
  val buckets = s3.listBuckets()
  buckets.toList.foreach { b =>
    if (b.getName() == leofsConfig.getString("bucket"))
      bucket = b
  }
  if (!bucket.isInstanceOf[Bucket]) {
    val newbucket = Try(s3.createBucket(leofsConfig.getString("bucket")))
    newbucket match {
      case Success(newbucket) =>
        bucket = newbucket
      case Failure(v) =>
        println("error:createBucket")
    }
  }

  def stringToStream(data:String) = {
    new ByteArrayInputStream(data.getBytes("UTF-8"))
  }

  def save(key: String, data: String) = {
    val meta = new ObjectMetadata()
      val customeMeta = Map("xitrum_content_type" -> "image")
      meta.setUserMetadata(customeMeta)
      meta.setContentLength(data.length)
      try {
        s3.putObject(new PutObjectRequest(bucket.getName(), key, stringToStream(data), meta));
      } catch {
        case ase: AmazonServiceException =>
          println("service")
          println(ase.getMessage())
          println(ase.getStatusCode())
        case ace: AmazonClientException =>
          println("client")
          println(ace.getMessage())
      }
  }

  def read(key: String):String = {
    val obj = Try(s3.getObject(new GetObjectRequest(bucket.getName(), key)))
    obj match {
      case Success(v) =>
        val content = v.getObjectContent()
        scala.io.Source.fromInputStream(content).getLines().mkString("")
      case Failure(v) =>
        "Error"
    }
  }

  def readHead(num: Int): Seq[String] = {
    var messages = Seq[String]()
    try {
      val objectListing = s3.listObjects(new ListObjectsRequest().withBucketName(bucket.getName()))
      objectListing.getObjectSummaries().toList.take(num).foreach { meta =>
        messages = messages :+ read(meta.getKey())
      }
    } catch {
      case ase: AmazonServiceException =>
        println("service")
        println(ase.getMessage())
        println(ase.getStatusCode())
      case ace: AmazonClientException =>
        println("client")
        println(ace.getMessage())
    }
    messages
  }
}