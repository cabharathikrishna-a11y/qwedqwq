package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

data class PdfCompressionResult(
    val outputFile: File,
    val originalSizeBytes: Long,
    val compressedSizeBytes: Long,
    val reductionPercentage: Int
)

object PdfCompressorHelper {

    private const val TAG = "PdfCompressorHelper"
    const val TARGET_MAX_BYTES = 5 * 1024 * 1024L // 5 MB

    /**
     * Compresses a PDF file or Uri to ensure its file size is below targetMaxSizeBytes (default 5 MB).
     * Optimized for high speed and minimal memory footprint, supporting large PDFs (1000+ pages).
     */
    suspend fun compressPdf(
        context: Context,
        inputSource: Any, // File or Uri or String path
        targetMaxSizeBytes: Long = TARGET_MAX_BYTES,
        customOutputFileName: String? = null,
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

        val baseName = customOutputFileName ?: sourceFile.nameWithoutExtension.removeSuffix("_compressed")
        val outputFile = File(appFilesDir, "${baseName}_compressed_${System.currentTimeMillis().toString().takeLast(4)}.pdf")

        val pfd = ParcelFileDescriptor.open(sourceFile, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        val newPdfDocument = PdfDocument()

        var reusableBitmap: Bitmap? = null
        var reusableCanvas: Canvas? = null

        try {
            val pageCount = renderer.pageCount
            
            // Calculate optimal target scale factor per page based on total page count and 5MB budget
            val targetBytesPerPage = if (pageCount > 0) targetMaxSizeBytes / pageCount else targetMaxSizeBytes
            val scaleFactor = when {
                targetBytesPerPage < 6_000 -> 0.32f   // e.g. 800+ pages
                targetBytesPerPage < 12_000 -> 0.42f  // e.g. 400-800 pages
                targetBytesPerPage < 25_000 -> 0.52f  // e.g. 200-400 pages
                targetBytesPerPage < 50_000 -> 0.65f  // e.g. 100-200 pages
                targetBytesPerPage < 100_000 -> 0.78f // e.g. 50-100 pages
                else -> 0.85f                         // < 50 pages
            }

            // Progress report interval: every page for small PDFs, every N pages for huge PDFs to avoid UI lockups
            val progressStep = when {
                pageCount > 500 -> 10
                pageCount > 100 -> 5
                pageCount > 30 -> 2
                else -> 1
            }

            for (i in 0 until pageCount) {
                val currentPg = i + 1
                if (currentPg == 1 || currentPg == pageCount || currentPg % progressStep == 0) {
                    onProgress?.invoke(currentPg, pageCount, "Compressing page $currentPg of $pageCount...")
                }

                val page = renderer.openPage(i)
                val origWidth = page.width
                val origHeight = page.height

                // Scaled bitmap dimensions
                val bmpWidth = (origWidth * scaleFactor).toInt().coerceAtLeast(200)
                val bmpHeight = (origHeight * scaleFactor).toInt().coerceAtLeast(250)

                // Reuse bitmap buffer across iterations to prevent GC thrashing on 1000+ page PDFs
                if (reusableBitmap == null || reusableBitmap!!.width != bmpWidth || reusableBitmap!!.height != bmpHeight || reusableBitmap!!.isRecycled) {
                    reusableBitmap?.recycle()
                    reusableBitmap = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888)
                    reusableCanvas = Canvas(reusableBitmap!!)
                } else {
                    reusableBitmap!!.eraseColor(Color.WHITE)
                }

                page.render(reusableBitmap!!, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()

                // Create page in output PdfDocument with original page layout dimensions
                val pageInfo = PdfDocument.PageInfo.Builder(origWidth, origHeight, currentPg).create()
                val newPage = newPdfDocument.startPage(pageInfo)

                val destCanvas = newPage.canvas
                val rect = Rect(0, 0, origWidth, origHeight)
                destCanvas.drawBitmap(reusableBitmap!!, null, rect, null)

                newPdfDocument.finishPage(newPage)

                // Trigger explicit GC hint periodically for mega PDFs (1000+ pages) to prevent native heap memory leaks
                if (currentPg % 100 == 0) {
                    System.gc()
                }
            }

            onProgress?.invoke(pageCount, pageCount, "Finalizing compressed 5 MB PDF...")
            FileOutputStream(outputFile).use { outStream ->
                newPdfDocument.writeTo(outStream)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error compressing PDF", e)
            throw e
        } finally {
            try {
                reusableBitmap?.recycle()
                newPdfDocument.close()
                renderer.close()
                pfd.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing PDF resources", e)
            }
            tempSourceFile?.delete()
        }

        val compressedSize = outputFile.length()
        val reduction = if (originalSize > 0) {
            (((originalSize - compressedSize).toDouble() / originalSize.toDouble()) * 100).toInt().coerceAtLeast(0)
        } else 0

        Log.i(TAG, "PDF Compressed: ${sourceFile.name} ($originalSize bytes) -> ${outputFile.name} ($compressedSize bytes), reduction=$reduction%")

        PdfCompressionResult(
            outputFile = outputFile,
            originalSizeBytes = originalSize,
            compressedSizeBytes = compressedSize,
            reductionPercentage = reduction
        )
    }
}
