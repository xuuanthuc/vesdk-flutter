package ly.img.flutter.video_editor_sdk

import android.app.Activity
import androidx.annotation.NonNull
import android.content.Intent
import android.net.Uri
import android.util.Log

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.Result
import ly.img.android.AuthorizationException

import ly.img.android.IMGLY
import ly.img.android.VESDK
import ly.img.android.pesdk.VideoEditorSettingsList
import ly.img.android.pesdk.backend.model.state.LoadSettings
import ly.img.android.pesdk.kotlin_extension.continueWithExceptions
import ly.img.android.pesdk.utils.UriHelper
import ly.img.android.sdk.config.*
import ly.img.android.pesdk.backend.encoder.Encoder
import ly.img.android.pesdk.backend.model.EditorSDKResult
import ly.img.android.pesdk.backend.model.state.VideoCompositionSettings
import ly.img.android.pesdk.utils.ThreadUtils
import ly.img.android.serializer._3.IMGLYFileWriter

import org.json.JSONObject
import java.io.File

import ly.img.flutter.imgly_sdk.FlutterIMGLY
import java.util.UUID

/** FlutterVESDK */
class FlutterVESDK: FlutterIMGLY() {

  companion object {
    // This number must be unique. It is public to allow client code to change it if the same value is used elsewhere.
    var EDITOR_RESULT_ID = 29064
  }

  override fun onAttachedToEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
    super.onAttachedToEngine(binding)

    channel = MethodChannel(binding.binaryMessenger, "video_editor_sdk")
    channel.setMethodCallHandler(this)
    IMGLY.initSDK(binding.applicationContext)
    IMGLY.authorize()
  }

  override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
    if (this.result != null) {
      result.error("Multiple requests.", "Cancelled due to multiple requests.", null)
      return
    }

    if (call.method == "openEditor") {
      var config = call.argument<MutableMap<String, Any>>("configuration")
      val serialization = call.argument<String>("serialization")

      if (config != null) {
        config = this.resolveAssets(config)
      }
      config = config as? HashMap<String, Any>

      val video = call.argument<MutableMap<String, Any>>("video")
      if (video != null) {
        val videosList = video["videos"] as ArrayList<String>?
        val videoSource = video["video"] as String?
        val size = video["size"] as? MutableMap<String, Double>

        this.result = result

        if (videoSource != null) {
          this.present(videoSource.let { EmbeddedAsset(it).resolvedURI }, config, serialization)
        } else {
          val videos = videosList?.mapNotNull { EmbeddedAsset(it).resolvedURI }
          this.present(videos, config, serialization, size)
        }
      } else {
        result.error("VE.SDK", "The video must not be null", null)
      }
    } else if (call.method == "unlock") {
      val license = call.argument<String>("license")
      this.result = result
      this.resolveLicense(license)
    } else {
      result.notImplemented()
    }
  }

  /**
   * Configures and presents the editor.
   *
   * @param asset The video source as *String* which should be loaded into the editor.
   * @param config The *Configuration* to configure the editor with as if any.
   * @param serialization The serialization to load into the editor if any.
   */
  override fun present(asset: String, config: HashMap<String, Any>?, serialization: String?) {
    val configuration = ConfigLoader.readFrom(config ?: mapOf())
    val settingsList = VideoEditorSettingsList(configuration.export?.serialization?.enabled == true)
    configuration.applyOn(settingsList)
    currentConfig = configuration

    settingsList.configure<LoadSettings> { loadSettings ->
      asset.also {
        loadSettings.source = retrieveURI(it)
      }
    }

    readSerialisation(settingsList, serialization, false)
    startEditor(settingsList, EDITOR_RESULT_ID)
  }

  /**
   * Configures and presents the editor.
   *
   * @param videos The video sources as *List<String>* which should be loaded into the editor.
   * @param config The *Configuration* to configure the editor with as if any.
   * @param serialization The serialization to load into the editor if any.
   */
  private fun present(videos: List<String>?, config: HashMap<String, Any>?, serialization: String?, size: Map<String, Any>?) {
    val configuration = ConfigLoader.readFrom(config ?: mapOf())
    val settingsList = VideoEditorSettingsList(configuration.export?.serialization?.enabled == true)
    configuration.applyOn(settingsList)
    currentConfig = configuration

    var source = resolveSize(size)
    if (videos != null && videos.count() > 0) {
      if (source == null) {
        if (size != null) {
          result?.error("VE.SDK", "Invalid video size: width and height must be greater than zero.", null)
          this.result = null
          return
        }
        val video = videos.first()
        source = retrieveURI(video)
      }

      settingsList.configure<VideoCompositionSettings> { loadSettings ->
        videos.forEach {
          val resolvedSource = retrieveURI(it)
          loadSettings.addCompositionPart(VideoCompositionSettings.VideoPart(resolvedSource))
        }
      }
    } else {
      if (source == null) {
        result?.error("VE.SDK", "A video composition without assets must have a specific size.", null)
        this.result = null
        return
      }
    }

    settingsList.configure<LoadSettings> {
      it.source = source
    }

    applyTheme(settingsList, configuration.theme)

    readSerialisation(settingsList, serialization, false)
    startEditor(settingsList, EDITOR_RESULT_ID)
  }

  private fun retrieveURI(source: String) : Uri {
    return if (source.startsWith("data:")) {
      UriHelper.createFromBase64String(source.substringAfter("base64,"))
    } else {
      val potentialFile = continueWithExceptions { File(source) }
      if (potentialFile?.exists() == true) {
        Uri.fromFile(potentialFile)
      } else {
        ConfigLoader.parseUri(source)
      }
    }
  }

  private fun resolveSize(size: Map<String, Any>?) : Uri? {
    val height = size?.get("height") as? Double ?: 0.0
    val width = size?.get("width") as? Double ?: 0.0
    if (height == 0.0 || width == 0.0) {
      return null
    }
    return LoadSettings.compositionSource(width.toInt(), height.toInt(), 60)
  }

  /**
   * Unlocks the SDK with a stringified license.
   *
   * @param license The license as a *String*.
   */
  override fun unlockWithLicense(license: String) {
    try {
      VESDK.initSDKWithLicenseData(license)
      IMGLY.authorize()
      this.result?.success(null)
      this.result = null
    } catch (e: AuthorizationException) {
      this.result?.error("VE.SDK", "The license is invalid.", e.message)
      this.result = null
    }
  }

  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
    val intentData = try {
      data?.let { EditorSDKResult(it) }
    } catch (e: EditorSDKResult.NotAnImglyResultException) {
      null
    } ?: return false // If intentData is null the result is not from us.

    if (resultCode == Activity.RESULT_CANCELED && requestCode == EDITOR_RESULT_ID) {
      currentActivity?.runOnUiThread {
        this.result?.success(null)
        this.result = null
      }
      return true
    } else if (resultCode == Activity.RESULT_OK && requestCode == EDITOR_RESULT_ID) {
      ThreadUtils.runAsync {
        val serializationConfig = currentConfig?.export?.serialization
        val resultUri = intentData.resultUri
        val sourceUri = intentData.sourceUri

        var serialization: Any? = null
        if (serializationConfig?.enabled == true) {
          val settingsList = intentData.settingsList
          skipIfNotExists {
            settingsList.let { settingsList ->
              serialization = when (serializationConfig.exportType) {
                SerializationExportType.FILE_URL -> {
                  val uri = serializationConfig.filename?.let {
                    Uri.parse("$it.json")
                  } ?: Uri.fromFile(File.createTempFile("serialization-" + UUID.randomUUID().toString(), ".json"))
                  Encoder.createOutputStream(uri).use { outputStream ->
                    IMGLYFileWriter(settingsList).writeJson(outputStream)
                  }
                  uri.toString()
                }
                SerializationExportType.OBJECT -> {
                  jsonToMap(JSONObject(IMGLYFileWriter(settingsList).writeJsonAsString()))
                }
              }
            }
            settingsList.release()
          } ?: run {
            Log.e("IMG.LY SDK", "You need to include 'backend:serializer' Module, to use serialisation!")
          }
        }

        val map = mutableMapOf<String, Any?>()
        map["video"] = resultUri.toString()
        map["hasChanges"] = (sourceUri?.path != resultUri?.path)
        map["serialization"] = serialization
        currentActivity?.runOnUiThread {
          this.result?.success(map)
          this.result = null
        }
      }
      return true
    }
    return false
  }
}
