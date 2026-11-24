package com.tat.quickreels

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Effect
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import java.io.File
import java.io.FileInputStream

@UnstableApi
object VideoExporter {
    fun export(
        context: Context,
        videoUri: Uri,
        overlay: Bitmap?,
        exportWidth: Int,
        exportHeight: Int,
        normTransX: Float,
        normTransY: Float,
        scaleX: Float,
        scaleY: Float,
        trimStartMs: Long,
        trimEndMs: Long,
        chromaKeyEnabled: Boolean = false,
        chromaKeyColor: Int = android.graphics.Color.GREEN,
        chromaKeyFeather: Float = 0.12f,
        chromaKeyEdgeSoftness: Float = 0.10f,
        onProgress: (Int) -> Unit,
        onStatus: (String, Boolean) -> Unit
    ): Transformer {
        val clippingConfiguration =
            MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(trimStartMs)
                .setEndPositionMs(trimEndMs)
                .build()
                
        val mediaItemBuilder = MediaItem.Builder()
            .setUri(videoUri)
        
        if (trimEndMs > trimStartMs) {
            mediaItemBuilder.setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(trimStartMs)
                    .setEndPositionMs(trimEndMs)
                    .build()
            )
        }
        
        val mediaItem = mediaItemBuilder.build()
    
        val temp = File(context.cacheDir, "tat_export.mp4")
        if (temp.exists()) temp.delete()

        val videoEffects = mutableListOf<Effect>(
            Presentation.createForWidthAndHeight(
                exportWidth,
                exportHeight,
                Presentation.LAYOUT_SCALE_TO_FIT
            ),
            MatrixTransformation { _ ->
                Matrix().apply {
                    postScale(scaleX, scaleY)
                    postTranslate(normTransX * 2f, -normTransY * 2f)
                }
            },
            Presentation.createForWidthAndHeight(
                exportWidth,
                exportHeight,
                Presentation.LAYOUT_SCALE_TO_FIT
            )
        )
        
        // Overlay bitmap থাকলেই সেটি effects-এ যোগ হবে।
        overlay?.let { bitmap ->
            videoEffects.add(
                OverlayEffect(
                    ImmutableList.of(
                        BitmapOverlay.createStaticBitmapOverlay(bitmap)
                    )
                )
            )
        }
        
        val edited = EditedMediaItem.Builder(mediaItem)
            .setEffects(
                Effects(
                    emptyList(),
                    videoEffects
                )
            )
            .build()

        val mainHandler = Handler(Looper.getMainLooper())
        var isExporting = true
        lateinit var transformer: Transformer

        val progressRunnable = object : Runnable {
            val holder = ProgressHolder()
            override fun run() {
                if (!isExporting) return
                val state = transformer.getProgress(holder)
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(holder.progress)
                }
                mainHandler.postDelayed(this, 200)
            }
        }

        transformer = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    isExporting = false
                    mainHandler.removeCallbacks(progressRunnable)
                    onProgress(100)
                    try {
                        overlay?.recycle()
                        val name = "TAT_${exportWidth}x${exportHeight}_${System.currentTimeMillis()}.mp4"
                        val values = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                            if (Build.VERSION.SDK_INT >= 29) {
                                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                            }
                        }

                        val resolver = context.contentResolver
                        val collection = if (Build.VERSION.SDK_INT >= 29) {
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI
                        } else {
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        }

                        val savedUri = resolver.insert(collection, values)
                            ?: throw Exception("Failed to create file")

                        resolver.openOutputStream(savedUri)?.use { output ->
                            FileInputStream(temp).use { input ->
                                input.copyTo(output)
                            }
                        } ?: throw Exception("Failed to save file")

                        temp.delete()
                        onStatus("Export completed! Saved $name to Downloads.", true)
                    } catch (e: Exception) {
                        onStatus("Save failed: ${e.message}", true)
                    }
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    isExporting = false
                    mainHandler.removeCallbacks(progressRunnable)
                    overlay?.recycle()
                    
                    onStatus("Export failed: ${exportException.message}", true)
                }
            })
            .build()

        onProgress(0)
        onStatus("Exporting... 0%", false)
        
        transformer.start(edited, temp.absolutePath)
        mainHandler.post(progressRunnable)
        
        return transformer
    }
}
