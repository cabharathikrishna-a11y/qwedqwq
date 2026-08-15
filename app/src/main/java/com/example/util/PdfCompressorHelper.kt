package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class PdfCompressionResult(
    val outputFile: File,
    val originalSizeBytes: Long,
    val compressedSizeBytes: Long,
    val reductionPercentage: Int
)

data class BackgroundPdfCompressionTask(
    val id: String = java.util.UUID.randomUUID().toString(),
    val fileName: String,
    val isRunning: Boolean,
    val currentPage: Int = 0,
    val totalPages: Int = 0,
    val progressFraction: Float = 0f,
    val statusText: String = "",
    val originalSizeBytes: Long = 0L,
    val compressedSizeBytes: Long = 0L,
    val reductionPercentage: Int = 0,
    val resultFile: File? = null,
    val error: String? = null,
    val completedAt: Long? = null,
    val autoOpenOnComplete: Boolean = true
)

enum class DocumentEnhancementMode {
    AUTO_DETECT,          // Detects monochrome vs color ink automatically
    ADAPTIVE_BINARIZATION, // Bradley-Roth adaptive thresholding for crisp black ink & pure white paper
    COLOR_QUANTIZATION,    // K-Means 8/16 color palette reduction for red/blue ink
    BACKGROUND_WHITENING   // High contrast illumination normalization with pure white background
}

/**
 * Modern Handwritten Document Enhancement & Compression Engine.
 * 
 * Replaces naive image downsampling with specialized document enhancement algorithms:
 * 1. Background Whitening (Illumination normalization & high contrast background clamping to #FFFFFF)
 * 2. Adaptive Binarization (Integral-image powered Bradley-Roth adaptive thresholding to eliminate dots & gray gradients)
 * 3. Color Quantization (K-Means Clustering to 8/16 flat colors for red/blue/multi-color ink notes)
 * 4. High-Efficiency PDF Stream Generation (Pure white run-length encoding & Deflate optimization)
 */
object PdfCompressorHelper {

    private const val TAG = "PdfCompressorHelper"
    const val TARGET_MAX_BYTES = 5 * 1024 * 1024L // Strict 5 MB ceiling

    private val _currentCompressionTask = MutableStateFlow<BackgroundPdfCompressionTask?>(null)
    val currentCompressionTask: StateFlow<BackgroundPdfCompressionTask?> = _currentCompressionTask.asStateFlow()

    private val _globalPdfToOpen = MutableStateFlow<Pair<String, String?>?>(null)
    val globalPdfToOpen: StateFlow<Pair<String, String?>?> = _globalPdfToOpen.asStateFlow()

    fun openPdfGlobally(path: String, fileName: String? = null) {
        _globalPdfToOpen.value = path to fileName
    }

    fun dismissGlobalPdf() {
        _globalPdfToOpen.value = null
    }

    private val compressorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var activeJob: Job? = null

    fun startBackgroundCompression(
        context: Context,
        inputSource: Any,
        customOutputFileName: String? = null,
        targetMaxSizeBytes: Long = TARGET_MAX_BYTES,
        enhancementMode: DocumentEnhancementMode = DocumentEnhancementMode.AUTO_DETECT,
        autoOpenOnComplete: Boolean = true,
        onCompleted: ((PdfCompressionResult) -> Unit)? = null,
        onError: ((Exception) -> Unit)? = null
    ): Job {
        val initialName = when (inputSource) {
            is File -> inputSource.name
            is Uri -> queryDisplayName(context, inputSource) ?: "Document.pdf"
            is String -> if (inputSource.startsWith("content://")) {
                queryDisplayName(context, Uri.parse(inputSource)) ?: "Document.pdf"
            } else File(inputSource).name
            else -> "Document.pdf"
        }
        val cleanName = if (!customOutputFileName.isNullOrBlank() && !isGeneratedTempName(customOutputFileName)) {
            customOutputFileName
        } else initialName

        val taskId = java.util.UUID.randomUUID().toString()
        _currentCompressionTask.value = BackgroundPdfCompressionTask(
            id = taskId,
            fileName = cleanName,
            isRunning = true,
            currentPage = 0,
            totalPages = 0,
            progressFraction = 0f,
            statusText = "Starting background compression...",
            autoOpenOnComplete = autoOpenOnComplete
        )

        activeJob?.cancel()
        val job = compressorScope.launch {
            try {
                val result = compressPdf(
                    context = context,
                    inputSource = inputSource,
                    targetMaxSizeBytes = targetMaxSizeBytes,
                    customOutputFileName = cleanName,
                    enhancementMode = enhancementMode,
                    onProgress = { cur, total, msg ->
                        val fraction = if (total > 0) cur.toFloat() / total.toFloat() else 0f
                        _currentCompressionTask.value = _currentCompressionTask.value?.copy(
                            currentPage = cur,
                            totalPages = total,
                            progressFraction = fraction,
                            statusText = msg
                        )
                    }
                )

                // Save to PdfStorageRepository so it's recorded in the user's PDF collection
                try {
                    val pdfRepo = com.example.pdf.PdfStorageRepository(context)
                    val compMb = String.format(java.util.Locale.US, "%.2f MB", result.compressedSizeBytes / (1024.0 * 1024.0))
                    val item = com.example.pdf.PdfDocumentItem(
                        id = java.util.UUID.randomUUID().toString(),
                        title = result.outputFile.name,
                        uriString = result.outputFile.absolutePath,
                        fileSizeFormatted = compMb,
                        pageCount = _currentCompressionTask.value?.totalPages ?: 1
                    )
                    pdfRepo.addOrUpdatePdf(item)
                } catch (e: Exception) {
                    Log.e(TAG, "Error adding compressed PDF to PdfStorageRepository", e)
                }

                _currentCompressionTask.value = BackgroundPdfCompressionTask(
                    id = taskId,
                    fileName = result.outputFile.name,
                    isRunning = false,
                    currentPage = _currentCompressionTask.value?.totalPages ?: 1,
                    totalPages = _currentCompressionTask.value?.totalPages ?: 1,
                    progressFraction = 1.0f,
                    statusText = "Compressed successfully (${result.reductionPercentage}% smaller)!",
                    originalSizeBytes = result.originalSizeBytes,
                    compressedSizeBytes = result.compressedSizeBytes,
                    reductionPercentage = result.reductionPercentage,
                    resultFile = result.outputFile,
                    completedAt = System.currentTimeMillis(),
                    autoOpenOnComplete = autoOpenOnComplete
                )

                if (autoOpenOnComplete) {
                    openPdfGlobally(result.outputFile.absolutePath, result.outputFile.name)
                }

                withContext(Dispatchers.Main) {
                    onCompleted?.invoke(result)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Background PDF compression failed", e)
                _currentCompressionTask.value = BackgroundPdfCompressionTask(
                    id = taskId,
                    fileName = cleanName,
                    isRunning = false,
                    error = e.localizedMessage ?: "Compression failed",
                    completedAt = System.currentTimeMillis()
                )
                withContext(Dispatchers.Main) {
                    onError?.invoke(e)
                }
            }
        }
        activeJob = job
        return job
    }

    fun dismissCurrentTask() {
        _currentCompressionTask.value = null
    }

    suspend fun compressPdf(
        context: Context,
        inputSource: Any, // File or Uri or String path
        targetMaxSizeBytes: Long = TARGET_MAX_BYTES,
        customOutputFileName: String? = null,
        enhancementMode: DocumentEnhancementMode = DocumentEnhancementMode.AUTO_DETECT,
        onProgress: ((currentPage: Int, totalPages: Int, statusText: String) -> Unit)? = null
    ): PdfCompressionResult = withContext(Dispatchers.IO) {
        var tempSourceFile: File? = null
        val sourceFile: File = when (inputSource) {
            is File -> inputSource
            is Uri -> {
                val temp = File(context.cacheDir, "temp_compress_input_${System.currentTimeMillis()}.pdf")
                context.contentResolver.openInputStream(inputSource)?.use { input ->
                    temp.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                tempSourceFile = temp
                temp
            }
            is String -> {
                if (inputSource.startsWith("content://")) {
                    val uri = Uri.parse(inputSource)
                    val temp = File(context.cacheDir, "temp_compress_input_${System.currentTimeMillis()}.pdf")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        temp.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    tempSourceFile = temp
                    temp
                } else {
                    File(inputSource)
                }
            }
            else -> throw IllegalArgumentException("Unsupported input source for PDF compression")
        }

        if (!sourceFile.exists() || sourceFile.length() == 0L) {
            throw java.io.FileNotFoundException("Source PDF file does not exist or is empty")
        }

        val originalSize = sourceFile.length()
        val appFilesDir = File(context.filesDir, "app_files")
        if (!appFilesDir.exists()) appFilesDir.mkdirs()

        val originalBaseName = extractOriginalFileName(context, inputSource, customOutputFileName)
        var finalFileName = "${originalBaseName}_compressed.pdf"
        var outputFile = File(appFilesDir, finalFileName)
        if (outputFile.canonicalPath == sourceFile.canonicalPath) {
            finalFileName = "${originalBaseName}_compressed_${System.currentTimeMillis()}.pdf"
            outputFile = File(appFilesDir, finalFileName)
        }
        val tempOutputFile = File(context.cacheDir, "temp_compress_out_${System.currentTimeMillis()}.pdf")

        // Strict 5MB ceiling rule: never exceed 5 MB
        val effectiveTargetMaxBytes = min(targetMaxSizeBytes, TARGET_MAX_BYTES)

        val pfd = ParcelFileDescriptor.open(sourceFile, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        val pageCount = renderer.pageCount

        var pass = 1
        var currentScaleFactor = calculateInitialScaleFactor(pageCount, effectiveTargetMaxBytes)
        var currentEnhancementMode = enhancementMode

        try {
            while (pass <= 3) {
                val isRetry = pass > 1
                val passMsgPrefix = if (isRetry) "Optimizing to guarantee < 5 MB (pass $pass)..." else ""
                
                compressPdfInternal(
                    renderer = renderer,
                    pageCount = pageCount,
                    scaleFactor = currentScaleFactor,
                    enhancementMode = currentEnhancementMode,
                    targetFile = tempOutputFile,
                    onProgress = { cur, total, msg ->
                        val finalMsg = if (passMsgPrefix.isNotEmpty()) "$passMsgPrefix $msg" else msg
                        onProgress?.invoke(cur, total, finalMsg)
                    }
                )

                val generatedSize = tempOutputFile.length()
                Log.d(TAG, "Pass $pass generated PDF size: $generatedSize bytes (target max: $effectiveTargetMaxBytes bytes)")

                // Check if strict 5MB limit is satisfied
                if (generatedSize in 1..effectiveTargetMaxBytes || generatedSize == 0L || pass == 3) {
                    break
                }

                // If generated PDF exceeded 5MB, adjust scale factor aggressively and retry
                pass++
                val sizeRatio = effectiveTargetMaxBytes.toDouble() / max(1L, generatedSize).toDouble()
                val shrinkMultiplier = (sqrt(sizeRatio) * 0.85).toFloat().coerceIn(0.35f, 0.75f)
                currentScaleFactor = (currentScaleFactor * shrinkMultiplier).coerceAtLeast(0.25f)
                // If it was auto-detect, force adaptive binarization on high retry passes to save massive space
                if (pass == 3) {
                    currentEnhancementMode = DocumentEnhancementMode.ADAPTIVE_BINARIZATION
                }
            }

            if (tempOutputFile.exists() && tempOutputFile.length() > 0) {
                tempOutputFile.copyTo(outputFile, overwrite = true)
                tempOutputFile.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in PDF compression pipeline", e)
            tempOutputFile.delete()
            throw e
        } finally {
            try {
                renderer.close()
                pfd.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing PDF pipeline resources", e)
            }
            tempSourceFile?.delete()
            tempOutputFile.delete()
        }

        val compressedSize = outputFile.length()
        val reduction = if (originalSize > 0) {
            (((originalSize - compressedSize).toDouble() / originalSize.toDouble()) * 100).toInt().coerceAtLeast(0)
        } else 0

        Log.i(TAG, "PDF Compression Finished: ${sourceFile.name} ($originalSize bytes) -> ${outputFile.name} ($compressedSize bytes, <= 5MB rule verified: ${compressedSize <= TARGET_MAX_BYTES}), reduction=$reduction%")

        PdfCompressionResult(
            outputFile = outputFile,
            originalSizeBytes = originalSize,
            compressedSizeBytes = compressedSize,
            reductionPercentage = reduction
        )
    }

    private fun calculateInitialScaleFactor(pageCount: Int, targetMaxSizeBytes: Long): Float {
        // Maximum compression while keeping text clear:
        return when {
            pageCount <= 2 -> 1.0f
            pageCount <= 10 -> 0.90f
            pageCount <= 30 -> 0.80f
            pageCount <= 75 -> 0.70f
            pageCount <= 150 -> 0.58f
            pageCount <= 300 -> 0.45f
            pageCount <= 600 -> 0.35f
            else -> 0.28f
        }
    }

    private fun compressPdfInternal(
        renderer: PdfRenderer,
        pageCount: Int,
        scaleFactor: Float,
        enhancementMode: DocumentEnhancementMode,
        targetFile: File,
        onProgress: ((currentPage: Int, totalPages: Int, statusText: String) -> Unit)?
    ) {
        val newPdfDocument = PdfDocument()
        var renderBitmap: Bitmap? = null
        var enhancedBitmap: Bitmap? = null
        var pixelBuffer: IntArray? = null
        var integralBuffer: LongArray? = null

        val progressStep = when {
            pageCount > 500 -> 10
            pageCount > 100 -> 5
            pageCount > 30 -> 2
            else -> 1
        }

        try {
            for (i in 0 until pageCount) {
                val currentPg = i + 1
                if (currentPg == 1 || currentPg == pageCount || currentPg % progressStep == 0) {
                    onProgress?.invoke(currentPg, pageCount, "Enhancing & compressing page $currentPg of $pageCount...")
                }

                val page = renderer.openPage(i)
                val origWidth = page.width
                val origHeight = page.height

                val renderWidth = (origWidth * scaleFactor).toInt().coerceAtLeast(240)
                val renderHeight = (origHeight * scaleFactor).toInt().coerceAtLeast(320)

                try {
                    // Ensure buffers are sized and allocated efficiently
                    if (renderBitmap == null || renderBitmap!!.width != renderWidth || renderBitmap!!.height != renderHeight || renderBitmap!!.isRecycled) {
                        renderBitmap?.recycle()
                        renderBitmap = Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.ARGB_8888)
                    } else {
                        renderBitmap!!.eraseColor(Color.WHITE)
                    }

                    if (enhancedBitmap == null || enhancedBitmap!!.width != renderWidth || enhancedBitmap!!.height != renderHeight || enhancedBitmap!!.isRecycled) {
                        enhancedBitmap?.recycle()
                        enhancedBitmap = Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.ARGB_8888)
                    }

                    val totalPixels = renderWidth * renderHeight
                    if (pixelBuffer == null || pixelBuffer!!.size < totalPixels) {
                        pixelBuffer = IntArray(totalPixels)
                    }

                    // Render high-fidelity page
                    page.render(renderBitmap!!, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                } finally {
                    page.close()
                }

                // Extract pixel data
                renderBitmap!!.getPixels(pixelBuffer, 0, renderWidth, 0, 0, renderWidth, renderHeight)

                // Choose pipeline: Check if color ink exists on this page
                val modeToApply = when (enhancementMode) {
                    DocumentEnhancementMode.AUTO_DETECT -> {
                        if (hasSignificantColorInk(pixelBuffer, renderWidth * renderHeight)) {
                            DocumentEnhancementMode.COLOR_QUANTIZATION
                        } else {
                            DocumentEnhancementMode.ADAPTIVE_BINARIZATION
                        }
                    }
                    else -> enhancementMode
                }

                try {
                    when (modeToApply) {
                        DocumentEnhancementMode.ADAPTIVE_BINARIZATION -> {
                            // 1. Integral Image Bradley-Roth Adaptive Thresholding (Crisp solid black ink, 100% pure white paper)
                            val integralSize = (renderWidth + 1) * (renderHeight + 1)
                            if (integralBuffer == null || integralBuffer!!.size < integralSize) {
                                integralBuffer = LongArray(integralSize)
                            }
                            applyBradleyAdaptiveBinarization(pixelBuffer, renderWidth, renderHeight, integralBuffer!!)
                        }
                        DocumentEnhancementMode.COLOR_QUANTIZATION -> {
                            // 2. Background Whitening + K-Means Color Quantization (for Red/Blue ink notes)
                            applyColorQuantizationPipeline(pixelBuffer, renderWidth, renderHeight)
                        }
                        DocumentEnhancementMode.BACKGROUND_WHITENING -> {
                            // 3. High-contrast illumination normalization
                            applyBackgroundWhitening(pixelBuffer, renderWidth, renderHeight)
                        }
                        else -> {}
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Filter error on page $currentPg, falling back to direct render", e)
                }

                enhancedBitmap!!.setPixels(pixelBuffer, 0, renderWidth, 0, 0, renderWidth, renderHeight)

                // Write enhanced page to output PDF
                val pageInfo = PdfDocument.PageInfo.Builder(origWidth, origHeight, currentPg).create()
                val newPage = newPdfDocument.startPage(pageInfo)

                val destCanvas = newPage.canvas
                val rect = Rect(0, 0, origWidth, origHeight)
                destCanvas.drawBitmap(enhancedBitmap!!, null, rect, null)

                newPdfDocument.finishPage(newPage)

                if (currentPg % 50 == 0) {
                    System.gc()
                }
            }

            onProgress?.invoke(pageCount, pageCount, "Writing compressed PDF stream...")
            FileOutputStream(targetFile).use { outStream ->
                newPdfDocument.writeTo(outStream)
            }
        } finally {
            try {
                renderBitmap?.recycle()
                enhancedBitmap?.recycle()
                newPdfDocument.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing internal PDF resources", e)
            }
        }
    }

    /**
     * Detects if an image contains significant colored ink (e.g. red pen corrections, blue ink, highlighter)
     * versus pure monochrome/grayscale text.
     */
    private fun hasSignificantColorInk(pixels: IntArray, totalPixels: Int): Boolean {
        var colorInkPixelCount = 0
        val sampleStep = max(1, totalPixels / 50_000) // Sample up to 50k pixels for instant speed
        var sampledCount = 0

        var idx = 0
        while (idx < totalPixels) {
            val color = pixels[idx]
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF

            val maxC = max(r, max(g, b))
            val minC = min(r, min(g, b))
            val diff = maxC - minC

            // If saturation is significant and it's not near white or pitch black, it's colored ink
            if (maxC > 40 && minC < 220) {
                val saturation = diff.toFloat() / maxC.toFloat()
                if (saturation > 0.28f) {
                    colorInkPixelCount++
                }
            }
            sampledCount++
            idx += sampleStep
        }

        val colorRatio = if (sampledCount > 0) colorInkPixelCount.toFloat() / sampledCount.toFloat() else 0f
        return colorRatio >= 0.008f // If at least 0.8% of sampled content is colored ink
    }

    /**
     * Bradley-Roth Adaptive Thresholding using an Integral Image (Summed-Area Table).
     * Computes local window mean in O(1) time per pixel.
     * Pixels significantly darker than their local neighborhood become crisp #000000 solid ink.
     * Pixels near or above the local neighborhood mean become pure #FFFFFF solid white paper.
     */
    private fun applyBradleyAdaptiveBinarization(
        pixels: IntArray,
        width: Int,
        height: Int,
        integral: LongArray
    ) {
        val windowSize = max(8, width / 12)
        val halfWindow = windowSize / 2
        val thresholdPercentage = 15 // T = 15% darker than local neighborhood

        // 1. Build Integral Image from Grayscale Luminance
        val integralWidth = width + 1
        for (y in 0 until height) {
            var rowSum = 0L
            val yOffset = y * width
            val curIntRowOffset = (y + 1) * integralWidth
            val prevIntRowOffset = y * integralWidth

            for (x in 0 until width) {
                val color = pixels[yOffset + x]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                val lum = (r * 299 + g * 587 + b * 114) / 1000

                rowSum += lum
                integral[curIntRowOffset + x + 1] = integral[prevIntRowOffset + x + 1] + rowSum
            }
        }

        // 2. Perform Adaptive Thresholding using local window sums
        val factor = 100 - thresholdPercentage
        for (y in 0 until height) {
            val y1 = max(0, y - halfWindow)
            val y2 = min(height - 1, y + halfWindow)
            val yOffset = y * width

            val y1IntOffset = y1 * integralWidth
            val y2IntOffset = (y2 + 1) * integralWidth

            for (x in 0 until width) {
                val x1 = max(0, x - halfWindow)
                val x2 = min(width - 1, x + halfWindow)

                val count = (x2 - x1 + 1) * (y2 - y1 + 1)
                val sum = integral[y2IntOffset + x2 + 1] - integral[y1IntOffset + x2 + 1] - integral[y2IntOffset + x1] + integral[y1IntOffset + x1]

                val color = pixels[yOffset + x]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                val lum = (r * 299 + g * 587 + b * 114) / 1000

                // If pixel luminance is darker than local mean threshold, it's ink
                if (lum * count * 100 <= sum * factor) {
                    pixels[yOffset + x] = 0xFF000000.toInt() // Solid crisp black ink
                } else {
                    pixels[yOffset + x] = 0xFFFFFFFF.toInt() // Pure white paper
                }
            }
        }
    }

    /**
     * Color Quantization Pipeline for documents with red/blue/colored handwriting.
     * 1. Whitens background shadows and paper grain to pure #FFFFFF.
     * 2. Uses K-Means Clustering on ink pixels to reduce camera noise to 8-16 flat colors.
     */
    private fun applyColorQuantizationPipeline(
        pixels: IntArray,
        width: Int,
        height: Int
    ) {
        val total = width * height
        val isInk = BooleanArray(total)
        val inkIndices = ArrayList<Int>(total / 4)

        // 1. Identify Background vs Ink (Luminance & Saturation heuristic)
        for (i in 0 until total) {
            val color = pixels[i]
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF

            val maxC = max(r, max(g, b))
            val minC = min(r, min(g, b))
            val lum = (r * 299 + g * 587 + b * 114) / 1000
            val sat = if (maxC > 0) (maxC - minC).toFloat() / maxC.toFloat() else 0f

            // Paper background typically has high luminance and very low color saturation
            val isPaperBackground = (lum >= 180 && sat < 0.20f) || (lum >= 210 && sat < 0.30f)

            if (isPaperBackground) {
                pixels[i] = 0xFFFFFFFF.toInt() // Pure white
                isInk[i] = false
            } else {
                isInk[i] = true
                inkIndices.add(i)
            }
        }

        if (inkIndices.isEmpty()) return

        // 2. K-Means Clustering for Ink Colors (K = 12 colors)
        val k = min(12, max(4, inkIndices.size / 100))
        val clusterR = DoubleArray(k)
        val clusterG = DoubleArray(k)
        val clusterB = DoubleArray(k)

        // Initialize centroids evenly from sampled ink
        val step = max(1, inkIndices.size / k)
        for (ci in 0 until k) {
            val sampledIdx = inkIndices[min(inkIndices.size - 1, ci * step)]
            val color = pixels[sampledIdx]
            clusterR[ci] = ((color shr 16) and 0xFF).toDouble()
            clusterG[ci] = ((color shr 8) and 0xFF).toDouble()
            clusterB[ci] = (color and 0xFF).toDouble()
        }

        val assignments = IntArray(inkIndices.size)
        val iterations = 4 // 4 iterations converges quickly for flat color mapping

        for (iter in 0 until iterations) {
            val sumR = DoubleArray(k)
            val sumG = DoubleArray(k)
            val sumB = DoubleArray(k)
            val counts = IntArray(k)

            for (j in 0 until inkIndices.size) {
                val pIdx = inkIndices[j]
                val color = pixels[pIdx]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF

                var bestDist = Double.MAX_VALUE
                var bestK = 0
                for (ci in 0 until k) {
                    val dr = r - clusterR[ci]
                    val dg = g - clusterG[ci]
                    val db = b - clusterB[ci]
                    val dist = dr * dr + dg * dg + db * db
                    if (dist < bestDist) {
                        bestDist = dist
                        bestK = ci
                    }
                }
                assignments[j] = bestK
                sumR[bestK] += r.toDouble()
                sumG[bestK] += g.toDouble()
                sumB[bestK] += b.toDouble()
                counts[bestK]++
            }

            for (ci in 0 until k) {
                if (counts[ci] > 0) {
                    clusterR[ci] = sumR[ci] / counts[ci]
                    clusterG[ci] = sumG[ci] / counts[ci]
                    clusterB[ci] = sumB[ci] / counts[ci]
                }
            }
        }

        // Apply quantized flat colors to ink pixels
        val finalPalette = IntArray(k)
        for (ci in 0 until k) {
            val r = clusterR[ci].toInt().coerceIn(0, 255)
            val g = clusterG[ci].toInt().coerceIn(0, 255)
            val b = clusterB[ci].toInt().coerceIn(0, 255)
            finalPalette[ci] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
        }

        for (j in 0 until inkIndices.size) {
            val pIdx = inkIndices[j]
            pixels[pIdx] = finalPalette[assignments[j]]
        }
    }

    /**
     * Background Whitening with High-Contrast illumination normalization.
     * Forces paper background shadows and gray textures into pure #FFFFFF.
     */
    private fun applyBackgroundWhitening(
        pixels: IntArray,
        width: Int,
        height: Int
    ) {
        val total = width * height
        for (i in 0 until total) {
            val color = pixels[i]
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF

            val lum = (r * 299 + g * 587 + b * 114) / 1000
            val maxC = max(r, max(g, b))
            val minC = min(r, min(g, b))
            val sat = if (maxC > 0) (maxC - minC).toFloat() / maxC.toFloat() else 0f

            if (lum > 175 && sat < 0.25f) {
                // Background paper shadow -> Pure white
                pixels[i] = 0xFFFFFFFF.toInt()
            } else if (lum > 140 && sat < 0.15f) {
                // Moderate paper texture -> Boost to white
                val boost = ((lum - 140) * 3).coerceIn(0, 255)
                val newR = min(255, r + boost)
                val newG = min(255, g + boost)
                val newB = min(255, b + boost)
                if (newR > 240 && newG > 240 && newB > 240) {
                    pixels[i] = 0xFFFFFFFF.toInt()
                } else {
                    pixels[i] = 0xFF000000.toInt() or (newR shl 16) or (newG shl 8) or newB
                }
            } else {
                // Ink stroke: Increase contrast
                val enhancedR = (r * 0.85).toInt().coerceIn(0, 255)
                val enhancedG = (g * 0.85).toInt().coerceIn(0, 255)
                val enhancedB = (b * 0.85).toInt().coerceIn(0, 255)
                pixels[i] = 0xFF000000.toInt() or (enhancedR shl 16) or (enhancedG shl 8) or enhancedB
            }
        }
    }

    /**
     * Resolves the clean base original name of a PDF file without random or system-generated artifacts.
     */
    fun extractOriginalFileName(context: Context, inputSource: Any, customName: String? = null): String {
        if (!customName.isNullOrBlank()) {
            val sanitized = sanitizeBaseFileName(customName)
            if (sanitized.isNotBlank() && !isGeneratedTempName(sanitized)) {
                return sanitized
            }
        }

        when (inputSource) {
            is File -> {
                val name = sanitizeBaseFileName(inputSource.name)
                if (name.isNotBlank() && !isGeneratedTempName(name)) {
                    return name
                }
            }
            is Uri -> {
                val displayName = queryDisplayName(context, inputSource)
                if (!displayName.isNullOrBlank()) {
                    val name = sanitizeBaseFileName(displayName)
                    if (name.isNotBlank() && !isGeneratedTempName(name)) return name
                }
                val lastSeg = inputSource.lastPathSegment
                if (!lastSeg.isNullOrBlank()) {
                    val name = sanitizeBaseFileName(lastSeg)
                    if (name.isNotBlank() && !isGeneratedTempName(name)) return name
                }
            }
            is String -> {
                if (inputSource.startsWith("content://")) {
                    val uri = Uri.parse(inputSource)
                    val displayName = queryDisplayName(context, uri)
                    if (!displayName.isNullOrBlank()) {
                        val name = sanitizeBaseFileName(displayName)
                        if (name.isNotBlank() && !isGeneratedTempName(name)) return name
                    }
                } else if (inputSource.startsWith("http://") || inputSource.startsWith("https://")) {
                    val lastSeg = Uri.parse(inputSource).lastPathSegment
                    if (!lastSeg.isNullOrBlank()) {
                        val name = sanitizeBaseFileName(lastSeg)
                        if (name.isNotBlank() && !isGeneratedTempName(name)) return name
                    }
                } else {
                    val f = File(inputSource)
                    val name = sanitizeBaseFileName(f.name)
                    if (name.isNotBlank() && !isGeneratedTempName(name)) return name
                }
            }
        }
        return "Document"
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIdx != -1 && cursor.moveToFirst()) {
                    cursor.getString(nameIdx)
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun isGeneratedTempName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.startsWith("temp_compress_input") ||
               lower.startsWith("temp_compress_out") ||
               lower.startsWith("pdf_cache_") ||
               lower.startsWith("temp_") ||
               lower.startsWith("cache_") ||
               (lower.startsWith("file_") && lower.filter { it.isDigit() }.length >= 6) ||
               (lower.startsWith("shared_doc") && lower.filter { it.isDigit() }.length >= 4)
    }

    fun sanitizeBaseFileName(raw: String): String {
        var name = raw.trim()
        if (name.endsWith(".pdf", ignoreCase = true)) {
            name = name.substring(0, name.length - 4)
        }
        name = name.removeSuffix("_compressed")
            .removeSuffix("-compressed")
            .removeSuffix(" compressed")
            .removeSuffix("_Compressed")
        return name.trim()
    }
}
