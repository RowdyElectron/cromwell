package cromwell.backend.google.pipelines.common

import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
import common.assertion.CromwellTimeoutSpec
import cromwell.backend.BackendConfigurationDescriptor
import cromwell.backend.google.pipelines.common.PipelinesApiTestConfig._
import cromwell.cloudsupport.gcp.GoogleConfiguration
import cromwell.core.path.DefaultPathBuilder
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks

class PipelinesApiConfigurationSpec extends AnyFlatSpec with CromwellTimeoutSpec with Matchers with TableDrivenPropertyChecks with BeforeAndAfterAll {

  behavior of "PipelinesApiConfigurationSpec"

  val mockFile = DefaultPathBuilder.createTempFile()

  override def afterAll(): Unit = {
    mockFile.delete(swallowIOExceptions = true)
    ()
  }

  val globalConfig = ConfigFactory.parseString(
    s"""
      |google {
      |
      |  application-name = "cromwell"
      |
      |  auths = [
      |    {
      |      name = "application-default"
      |      scheme = "application_default"
      |    },
      |    {
      |      name = "user-via-refresh"
      |      scheme = "refresh_token"
      |      client-id = "secret_id"
      |      client-secret = "${mockFile.pathAsString}"
      |    },
      |    {
      |      name = "service-account"
      |      scheme = "service_account"
      |      service-account-id = "my-service-account"
      |      pem-file = "${mockFile.pathAsString}"
      |    }
      |  ]
      |}
      |
    """.stripMargin)

  val backendConfig = ConfigFactory.parseString(
    """
      |  // Google project
      |  project = "my-cromwell-workflows"
      |
      |  // Base bucket for workflow executions
      |  root = "gs://my-cromwell-workflows-bucket"
      |
      |  // Polling for completion backs-off gradually for slower-running jobs.
      |  // This is the maximum polling interval (in seconds):
      |  maximum-polling-interval = 600
      |
      |  genomics {
      |  // A reference to an auth defined in the `google` stanza at the top.  This auth is used to create
      |  // Pipelines and manipulate auth JSONs.
      |     auth = "application-default"
      |     // Endpoint for APIs, no reason to change this unless directed by Google.
      |     endpoint-url = "https://genomics.googleapis.com/"
      |  }
      |
      |  default-runtime-attributes {
      |      failOnStderr: false
      |      continueOnReturnCode: 0
      |      cpu: 1
      |      memory: "2 GB"
      |      bootDiskSizeGb: 10
      |      disks: "local-disk 10 SSD"
      |      noAddress: false
      |      preemptible: 3
      |      zones:["us-central1-a", "us-central1-b"]
      |  }
      |
      |  dockerhub {
      |    account = "dockerAccount"
      |    token = "dockerToken"
      |  }
      |
      |  filesystems {
      |    gcs {
      |      // A reference to a potentially different auth for manipulating files via engine functions.
      |      auth = "application-default"
      |    }
      |  }
      |
    """.stripMargin)

  it should "fail to instantiate if any required configuration is missing" in {

    val configs = Table(
      ("backendConfig", "globalConfig"),
      (backendConfig, globalConfig.withoutPath("google")),
      (backendConfig.withoutPath("project"), globalConfig),
      (backendConfig.withoutPath("root"), globalConfig),
      (backendConfig.withoutPath("genomics"), globalConfig),
      (backendConfig.withoutPath("genomics.endpoint-url"), globalConfig),
      (backendConfig.withoutPath("filesystems"), globalConfig),
      (backendConfig.withoutPath("filesystems.gcs"), globalConfig),
      (backendConfig.withoutPath("filesystems.gcs.auth"), globalConfig)
    )

    forAll(configs) { (backend, global) =>
      an[Exception] shouldBe thrownBy {
        val failingGoogleConf = GoogleConfiguration(global)
        val failingAttributes = PipelinesApiConfigurationAttributes(failingGoogleConf, backend, "papi")
        new PipelinesApiConfiguration(BackendConfigurationDescriptor(backend, global), genomicsFactory, failingGoogleConf, failingAttributes)
      }
    }
  }

  it should "have correct root" in {
    new PipelinesApiConfiguration(BackendConfigurationDescriptor(backendConfig, globalConfig), genomicsFactory, googleConfiguration, papiAttributes).root shouldBe "gs://my-cromwell-workflows-bucket"
  }

  it should "have correct docker" in {
    val dockerConf = new PipelinesApiConfiguration(BackendConfigurationDescriptor(backendConfig, globalConfig), genomicsFactory, googleConfiguration, papiAttributes).dockerCredentials
    dockerConf shouldBe defined
    dockerConf.get.token shouldBe "dockerToken"
  }

  it should "correctly default allowNoAddress to true" in {
    val noAddressConf = new PipelinesApiConfiguration(BackendConfigurationDescriptor(backendConfig, globalConfig), genomicsFactory, googleConfiguration, papiAttributes)
    noAddressConf.papiAttributes.allowNoAddress should be(true)
  }

  it should "be able to set allowNoAddress to false" in {
    val updatedBackendConfig = backendConfig.withValue(
      PipelinesApiConfigurationAttributes.allowNoAddressAttributeKey,
      ConfigValueFactory.fromAnyRef(false)
    )
    val updatedPapiAttributes = PipelinesApiConfigurationAttributes(googleConfiguration, updatedBackendConfig, "papi")
    val noAddressConf = new PipelinesApiConfiguration(BackendConfigurationDescriptor(updatedBackendConfig, globalConfig), genomicsFactory, googleConfiguration, updatedPapiAttributes)
    noAddressConf.papiAttributes.allowNoAddress should be(false)
  }

  it should "have correct needAuthFileUpload" in {
    val configs = Table(
      ("backendConfig", "globalConfig"),
      // With Docker
      (backendConfig.withValue("filesystems.gcs.auth", ConfigValueFactory.fromAnyRef("application-default")), true),
      (backendConfig.withValue("filesystems.gcs.auth", ConfigValueFactory.fromAnyRef("user-via-refresh")), true),
      (backendConfig.withValue("filesystems.gcs.auth", ConfigValueFactory.fromAnyRef("service-account")), true),
      // Without Docker
      (backendConfig.withValue("filesystems.gcs.auth", ConfigValueFactory.fromAnyRef("application-default")).withoutPath("dockerhub"), false),
      (backendConfig.withValue("filesystems.gcs.auth", ConfigValueFactory.fromAnyRef("user-via-refresh")).withoutPath("dockerhub"), true),
      (backendConfig.withValue("filesystems.gcs.auth", ConfigValueFactory.fromAnyRef("service-account")).withoutPath("dockerhub"), false)
    )

    forAll(configs) { (backend, needAuthFileUpload) =>
      val customGoogleConfig = GoogleConfiguration(globalConfig)
      val attributes = PipelinesApiConfigurationAttributes(customGoogleConfig, backend, "papi")
      new PipelinesApiConfiguration(BackendConfigurationDescriptor(backend, globalConfig), genomicsFactory, googleConfiguration, attributes).needAuthFileUpload shouldBe needAuthFileUpload
    }
  }
}
