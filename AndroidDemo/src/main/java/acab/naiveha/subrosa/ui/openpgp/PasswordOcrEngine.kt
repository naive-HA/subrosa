package acab.naiveha.subrosa.ui.openpgp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import androidx.core.graphics.scale

class PasswordOcrEngine(private val context: Context) {

    private val appContext = context.applicationContext

    companion object {
        private const val TAG = "PasswordOcrEngine"
        private const val TRAINED_DATA_ASSET = "tessdata/eng.traineddata"
        private const val TESSDATA_DIR = "tessdata"
        private const val LANGUAGE = "eng"
        private const val CHAR_WHITELIST = "0123456789-"
    }

    private var tessApi: TessBaseAPI? = null

    sealed class OcrResult {
        data class Success(val text: String, val confidence: Float) : OcrResult()
        data class Failure(val reason: String) : OcrResult()
    }

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            ensureTrainedDataOnDisk()

            val dataDir = appContext.filesDir.absolutePath
            val api = TessBaseAPI()

            val initialized = api.init(dataDir, LANGUAGE)
            if (!initialized) {
                Log.e(TAG, "TessBaseAPI failed to initialize with $dataDir")
                api.recycle()
                return@withContext false
            }

            api.setVariable(TessBaseAPI.VAR_CHAR_WHITELIST, CHAR_WHITELIST)
            api.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_LINE

            tessApi = api
            true
        } catch (e: Exception) {
            Log.e(TAG, "Exception during OCR initialization", e)
            false
        }
    }

    suspend fun recognize(bitmap: Bitmap, cropRect: Rect? = null): OcrResult =
        withContext(Dispatchers.Default) {
            val api = tessApi
                ?: return@withContext OcrResult.Failure("OCR engine not initialized")

            try {
                val inputBitmap = if (cropRect != null) {
                    val cropped = Bitmap.createBitmap(bitmap, cropRect.left, cropRect.top, cropRect.width(), cropRect.height())
                    
                    if (cropped.height < 100) {
                        Log.d(TAG, "Upscaling cropped image from ${cropped.width}x${cropped.height}")
                        cropped.scale(cropped.width * 2, cropped.height * 2)
                    } else {
                        cropped
                    }
                } else {
                    bitmap
                }

                Log.d(TAG, "Running OCR on bitmap: ${inputBitmap.width}x${inputBitmap.height}")
                api.setImage(inputBitmap)

                var rawText = api.utF8Text ?: ""
                var confidence = api.meanConfidence().toFloat()
                
                if (sanitize(rawText).isEmpty()) {
                    Log.d(TAG, "PSM_SINGLE_LINE returned no digits, falling back to PSM_AUTO")
                    api.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
                    api.setImage(inputBitmap)
                    rawText = api.utF8Text ?: ""
                    confidence = api.meanConfidence().toFloat()
                    api.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_LINE
                }

                Log.d(TAG, "OCR raw result: '$rawText', confidence: $confidence")

                val cleaned = sanitize(rawText)
                Log.d(TAG, "OCR cleaned result: '$cleaned'")

                if (cleaned.isEmpty()) {
                    Log.w(TAG, "OCR failed: No digits recognized in selected region")
                    OcrResult.Failure("No digits recognized in selected region")
                } else {
                    OcrResult.Success(cleaned, confidence)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Tesseract error during recognition", e)
                OcrResult.Failure("OCR failed: ${e.message}")
            }
        }

    private fun sanitize(raw: String): String =
        raw.trim().filter { it.isDigit() || it == '-' }

    fun close() {
        tessApi?.recycle()
        tessApi = null
    }

    private fun ensureTrainedDataOnDisk() {
        val tessDir = File(appContext.filesDir, TESSDATA_DIR)
        if (!tessDir.exists()) {
            tessDir.mkdirs()
        }

        val trainedDataFile = File(tessDir, "$LANGUAGE.traineddata")
        if (trainedDataFile.exists() && trainedDataFile.length() > 0) {
            return
        }

        val inputStream = try {
            context.assets.open(TRAINED_DATA_ASSET)
        } catch (e: IOException) {
            if (e.message?.contains("Failed to load asset path") == true) {
                val freshContext = context.createConfigurationContext(android.content.res.Configuration())
                freshContext.assets.open(TRAINED_DATA_ASSET)
            } else {
                throw e
            }
        }

        inputStream.use { input ->
            FileOutputStream(trainedDataFile).use { output ->
                input.copyTo(output)
            }
        }
    }
}
