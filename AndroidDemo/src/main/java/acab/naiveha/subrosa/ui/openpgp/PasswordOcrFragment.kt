package acab.naiveha.subrosa.ui.openpgp

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import acab.naiveha.subrosa.MainViewModel
import acab.naiveha.subrosa.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PasswordOcrFragment : Fragment() {
    companion object {
        private const val TAG = "PasswordOcrFragment"

        private const val MAX_OCR_IMAGE_DIMENSION = 4096
    }

    private val viewModel: PasswordOcrViewModel by viewModels()
    private val activityViewModel: MainViewModel by activityViewModels()
    private lateinit var ocrEngine: PasswordOcrEngine
    private var lastDisplayedBitmap: Bitmap? = null

    private lateinit var imageView: ImageView
    private lateinit var cropOverlay: CropSelectionView
    private lateinit var confirmButton: Button
    private lateinit var statusText: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_password_ocr, container, false)

        imageView = root.findViewById(R.id.imageView)
        cropOverlay = root.findViewById(R.id.cropOverlay)
        confirmButton = root.findViewById(R.id.confirmButton)
        statusText = root.findViewById(R.id.statusText)

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ocrEngine = PasswordOcrEngine(requireContext())

        activityViewModel.pendingOcrUri.observe(viewLifecycleOwner) { uri ->
            Log.d(TAG, "pendingOcrUri observer: $uri")
            if (uri != null) {
                activityViewModel.consumeOcrUri()
                decodeAndImport(uri)
            }
        }

        lifecycleScope.launch {
            val ready = ocrEngine.initialize()
            if (!ready) {
                statusText.text = "OCR engine failed to initialize"
                confirmButton.isEnabled = false
            }
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Log.d(TAG, "handleOnBackPressed: terminating flow")
                findNavController().popBackStack(R.id.nav_management, false)
            }
        })

        viewModel.uiState.observe(viewLifecycleOwner) { state ->
            if (state.bitmap != null && state.bitmap !== lastDisplayedBitmap) {
                lastDisplayedBitmap = state.bitmap
                displayBitmap(state.bitmap)
            }
            renderState(state.ocrState)
        }

        confirmButton.setOnClickListener { runOcrOnSelection() }
    }

    private fun decodeAndImport(uri: Uri) {
        lifecycleScope.launch {
            val bitmap = decodeUri(uri)
            if (bitmap == null) {
                Toast.makeText(requireContext(), "That image couldn't be read — try a smaller photo", Toast.LENGTH_LONG).show()
            }
            viewModel.setImportedBitmap(bitmap)
        }
    }

    private suspend fun decodeUri(uri: Uri): Bitmap? =
        withContextIo {
            val resolver = requireContext().applicationContext.contentResolver

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }

            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                Log.w(TAG, "decodeUri: unreadable or zero-size image, rejecting")
                return@withContextIo null
            }
            if (bounds.outWidth > MAX_OCR_IMAGE_DIMENSION || bounds.outHeight > MAX_OCR_IMAGE_DIMENSION) {
                Log.w(
                    TAG,
                    "decodeUri: image ${bounds.outWidth}x${bounds.outHeight} exceeds " +
                        "${MAX_OCR_IMAGE_DIMENSION}px, rejecting"
                )
                return@withContextIo null
            }

            resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        }

    private fun displayBitmap(bitmap: Bitmap) {
        imageView.setImageBitmap(bitmap)
        cropOverlay.clearSelection()

        imageView.post {
            cropOverlay.bitmapWidth = bitmap.width
            cropOverlay.bitmapHeight = bitmap.height
            cropOverlay.imageDisplayBounds = computeDisplayedImageBounds(imageView, bitmap)
        }
    }

    private fun computeDisplayedImageBounds(iv: ImageView, bitmap: Bitmap): RectF {
        val viewWidth = iv.width.toFloat()
        val viewHeight = iv.height.toFloat()
        val bmpWidth = bitmap.width.toFloat()
        val bmpHeight = bitmap.height.toFloat()

        val viewRatio = viewWidth / viewHeight
        val bmpRatio = bmpWidth / bmpHeight

        return if (bmpRatio > viewRatio) {
            val displayedHeight = viewWidth / bmpRatio
            val top = (viewHeight - displayedHeight) / 2f
            RectF(0f, top, viewWidth, top + displayedHeight)
        } else {
            val displayedWidth = viewHeight * bmpRatio
            val left = (viewWidth - displayedWidth) / 2f
            RectF(left, 0f, left + displayedWidth, viewHeight)
        }
    }

    private fun runOcrOnSelection() {
        val bitmap = viewModel.uiState.value?.bitmap
        if (bitmap == null) {
            Toast.makeText(requireContext(), "No image imported", Toast.LENGTH_SHORT).show()
            return
        }

        val cropRect = cropOverlay.selectionInImageCoordinates()
        if (cropRect == null) {
            Toast.makeText(requireContext(), "Drag a box around the password first", Toast.LENGTH_SHORT).show()
            return
        }

        viewModel.setState(PasswordOcrViewModel.OcrUiState.Recognizing)

        lifecycleScope.launch {
            when (val result = ocrEngine.recognize(bitmap, cropRect)) {
                is PasswordOcrEngine.OcrResult.Success -> {
                    if (result.confidence >= 10f) {
                        viewModel.setState(
                            PasswordOcrViewModel.OcrUiState.Recognized(result.text, result.confidence)
                        )
                        copyToClipboard(result.text)
                        
                        Toast.makeText(requireContext(), "Password extracted and copied to clipboard", Toast.LENGTH_LONG).show()

                        findNavController().navigate(R.id.nav_openpgp, null, NavOptions.Builder()
                            .setPopUpTo(R.id.passwordOcrFragment, true)
                            .setLaunchSingleTop(true)
                            .build())
                    } else {
                        vibrate()
                        val errorMsg = "ERROR: could not identify the password with sufficient confidence"
                        viewModel.setState(PasswordOcrViewModel.OcrUiState.Error(errorMsg))
                        Toast.makeText(requireContext(), errorMsg, Toast.LENGTH_LONG).show()
                    }
                }
                is PasswordOcrEngine.OcrResult.Failure -> {
                    vibrate()
                    viewModel.setState(PasswordOcrViewModel.OcrUiState.Error(result.reason))
                }
            }
        }
    }

    private fun vibrate() {
        val vibratorManager = requireContext().getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        val vibrator = vibratorManager.defaultVibrator
        vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun renderState(state: PasswordOcrViewModel.OcrUiState) {
        statusText.text = when (state) {
            is PasswordOcrViewModel.OcrUiState.AwaitingSelection ->
                "Drag a box around the password, then tap Extract"
            is PasswordOcrViewModel.OcrUiState.Recognizing ->
                "Recognizing..."
            is PasswordOcrViewModel.OcrUiState.Recognized ->
                "Copied to clipboard (confidence ${state.confidence.toInt()}%)"
            is PasswordOcrViewModel.OcrUiState.Error ->
                "Error: ${state.message}"
        }
    }

    private fun copyToClipboard(password: String) {
        val clipboardManager =
            requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        val clip = ClipData.newPlainText("Static password", password)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val extras = PersistableBundle()
            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            clip.description.extras = extras
        }

        clipboardManager.setPrimaryClip(clip)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(requireContext(), "Password copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        ocrEngine.close()
    }

    private suspend fun <T> withContextIo(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { block() }
}
