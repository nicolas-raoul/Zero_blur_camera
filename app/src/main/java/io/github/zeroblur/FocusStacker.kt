package io.github.zeroblur

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.ArrayList

class FocusStacker(private val context: Context) {

    private val TAG = "ZeroBlur"

    data class AlignedImage(val image: Mat)

    fun process(imageUris: List<Uri>, burstStartWallTime: Long, logCallback: (String) -> Unit): Uri? {
        val log = { msg: String ->
            Log.i(TAG, msg)
            logCallback(msg)
        }

        log("Starting focus stacking with ${imageUris.size} images (Laplacian Pyramid)")

        if (imageUris.isEmpty()) {
            log("No images to process")
            return null
        }

        val mats = ArrayList<Mat>()
        try {
            // 1. Load images
            for ((index, uri) in imageUris.withIndex()) {
                log("Loading image ${index + 1}...")
                val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
                if (inputStream != null) {
                    val bytes = inputStream.readBytes()
                    inputStream.close()
                    val mat = Imgcodecs.imdecode(MatOfByte(*bytes), Imgcodecs.IMREAD_COLOR)
                    if (!mat.empty()) {
                        Imgproc.cvtColor(mat, mat, Imgproc.COLOR_BGR2RGB) // Convert to RGB for processing
                        mats.add(mat)
                    } else {
                        log("Failed to decode image $index")
                    }
                } else {
                    log("Failed to open input stream for image $index")
                }
            }

            if (mats.isEmpty()) {
                log("No valid images loaded")
                return null
            }

            // 2. Align images (ECC)
            log("Aligning images using ECC...")
            val alignedImages = alignImagesECC(mats, log)

            // 3. Merge images (Laplacian Pyramid)
            log("Merging images (Laplacian Pyramid Fusion)...")
            val resultMat = mergeImagesLaplacianPyramid(alignedImages, log)

            // 4. Save result
            log("Saving result...")
            val refUri = if (imageUris.isNotEmpty()) imageUris[imageUris.size / 2] else null
            val resultUri = saveResult(resultMat, burstStartWallTime, refUri)
            
            if (resultUri != null) {
                log("Saved and scanned: $resultUri")
            }

            return resultUri

        } catch (e: Exception) {
            Log.e(TAG, "Error during focus stacking", e)
            log("Error: ${e.message}")
            return null
        } finally {
            // Cleanup
            mats.forEach { it.release() }
        }
    }
    
    // ... (alignImagesECC, mergeImagesLaplacianPyramid, etc. unchanged) ...

    private fun saveResult(mat: Mat, burstStartWallTime: Long, refUri: Uri?): Uri? {
        val folder = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DCIM), "Camera")
        if (!folder.exists()) folder.mkdirs()

        val sdf = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
        val filename = "ZeroBlur_${sdf.format(java.util.Date(burstStartWallTime))}.jpg"
        val file = File(folder, filename)

        try {
            val bitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(mat, bitmap)

            val out = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
            out.flush()
            out.close()
            
            // Copy EXIF before scanning
            if (refUri != null) {
                copyExif(refUri, file)
            }

            // Scan file and wait for Uri
            var contentUri: Uri? = null
            val latch = java.util.concurrent.CountDownLatch(1)
            android.media.MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf("image/jpeg")
            ) { _, uri ->
                contentUri = uri
                latch.countDown()
            }
            
            try {
                latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: Exception) {
                Log.e(TAG, "Timeout waiting for MediaScanner", e)
            }
            
            return contentUri ?: Uri.fromFile(file)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save stacked image", e)
            return null
        }
    }

    private fun alignImagesECC(mats: List<Mat>, log: (String) -> Unit): List<AlignedImage> {
        if (mats.isEmpty()) return emptyList()
        
        val alignedImages = ArrayList<AlignedImage>()
        val refIndex = mats.size / 2
        val refMat = mats[refIndex]
        
        // Convert reference to gray for ECC
        val refGray = Mat()
        Imgproc.cvtColor(refMat, refGray, Imgproc.COLOR_RGB2GRAY)
        
        // Downscale for speed? ECC is slow on full res.
        // Let's use a max dimension of 1000px for alignment calculation
        val scale = 1000.0 / Math.max(refMat.cols(), refMat.rows())
        val smallSize = Size(refMat.cols() * scale, refMat.rows() * scale)
        val smallRefGray = Mat()
        Imgproc.resize(refGray, smallRefGray, smallSize)

        // Initialize alignedMats array with nulls
        val alignedMats = arrayOfNulls<Mat>(mats.size)
        alignedMats[refIndex] = refMat.clone()

        for (i in mats.indices) {
            if (i == refIndex) continue

            log("Aligning image ${i + 1} to reference ${refIndex + 1}...")
            
            val currentMat = mats[i]
            val currentGray = Mat()
            Imgproc.cvtColor(currentMat, currentGray, Imgproc.COLOR_RGB2GRAY)
            val smallCurrentGray = Mat()
            Imgproc.resize(currentGray, smallCurrentGray, smallSize)

            // Initialize warp matrix (Identity)
            val warpMatrix = Mat.eye(2, 3, CvType.CV_32F)
            
            try {
                // Run ECC
                // MOTION_AFFINE is usually good enough and robust
                val criteria = TermCriteria(TermCriteria.COUNT + TermCriteria.EPS, 50, 0.001)
                Video.findTransformECC(smallRefGray, smallCurrentGray, warpMatrix, Video.MOTION_AFFINE, criteria, Mat())
                
                // Scale warp matrix up to full resolution
                // Translation elements (0,2) and (1,2) need to be scaled by (1/scale)
                // Rotation/Scale elements remain same
                val data = FloatArray(6)
                warpMatrix.get(0, 0, data)
                data[2] /= scale.toFloat()
                data[5] /= scale.toFloat()
                warpMatrix.put(0, 0, data)

                // Warp the full resolution image
                val aligned = Mat()
                Imgproc.warpAffine(currentMat, aligned, warpMatrix, refMat.size(), Imgproc.INTER_LINEAR + Imgproc.WARP_INVERSE_MAP)
                
                alignedMats[i] = aligned
                log("Aligned image ${i + 1}")

            } catch (e: Exception) {
                log("ECC failed for image ${i + 1}, using original. Error: ${e.message}")
                alignedMats[i] = currentMat.clone()
            }
        }

        return alignedMats.filterNotNull().map { AlignedImage(it) }
    }

    private fun mergeImagesLaplacianPyramid(alignedImages: List<AlignedImage>, log: (String) -> Unit): Mat {
        if (alignedImages.isEmpty()) return Mat()
        
        val images = alignedImages.map { it.image }
        val levels = 5 // Number of pyramid levels
        
        log("Building Gaussian Pyramids...")
        val gaussianPyramids = images.map { buildGaussianPyramid(it, levels) }
        
        log("Building Laplacian Pyramids...")
        val laplacianPyramids = gaussianPyramids.map { buildLaplacianPyramid(it) }
        
        log("Fusing Pyramids...")
        // Fuse each level
        val fusedPyramid = ArrayList<Mat>()
        for (l in 0 until levels) {
            fusedPyramid.add(fuseLevel(laplacianPyramids.map { it[l] }))
        }
        // Fuse the last Gaussian level (base)
        fusedPyramid.add(fuseLevel(gaussianPyramids.map { it[levels] }))
        
        log("Collapsing Pyramid...")
        return collapsePyramid(fusedPyramid)
    }

    private fun buildGaussianPyramid(img: Mat, levels: Int): List<Mat> {
        val pyramid = ArrayList<Mat>()
        var current = img.clone()
        // Convert to float for precision
        current.convertTo(current, CvType.CV_32F)
        pyramid.add(current)
        
        for (i in 0 until levels) {
            val next = Mat()
            Imgproc.pyrDown(current, next)
            pyramid.add(next)
            current = next
        }
        return pyramid
    }

    private fun buildLaplacianPyramid(gaussianPyramid: List<Mat>): List<Mat> {
        val pyramid = ArrayList<Mat>()
        for (i in 0 until gaussianPyramid.size - 1) {
            val current = gaussianPyramid[i]
            val next = gaussianPyramid[i + 1]
            val expanded = Mat()
            Imgproc.pyrUp(next, expanded, current.size())
            
            val laplacian = Mat()
            Core.subtract(current, expanded, laplacian)
            pyramid.add(laplacian)
        }
        return pyramid
    }

    private fun fuseLevel(layerImages: List<Mat>): Mat {
        // Simple max-absolute fusion
        // For each pixel, pick the value from the layer with the highest absolute value (highest contrast/detail)
        
        val rows = layerImages[0].rows()
        val cols = layerImages[0].cols()
        val type = layerImages[0].type()
        
        val fused = Mat.zeros(rows, cols, type)
        
        // Compute magnitude maps for each image
        val magnitudes = layerImages.map { img ->
            val gray = Mat()
            if (img.channels() == 3) {
                Imgproc.cvtColor(img, gray, Imgproc.COLOR_RGB2GRAY)
            } else {
                img.copyTo(gray)
            }
            val abs = Mat()
            Core.absdiff(gray, Scalar(0.0), abs) // Absolute value
            abs
        }
        
        val bestIndexMap = Mat.zeros(rows, cols, CvType.CV_8U)
        val maxMagnitude = Mat.zeros(rows, cols, CvType.CV_32F)
        
        magnitudes[0].copyTo(maxMagnitude)
        
        for (i in 1 until layerImages.size) {
            val mask = Mat()
            Core.compare(magnitudes[i], maxMagnitude, mask, Core.CMP_GT)
            magnitudes[i].copyTo(maxMagnitude, mask)
            
            val indexMat = Mat(rows, cols, CvType.CV_8U, Scalar(i.toDouble()))
            indexMat.copyTo(bestIndexMap, mask)
        }
        
        // Copy pixels based on bestIndexMap
        for (i in layerImages.indices) {
            val mask = Mat()
            Core.compare(bestIndexMap, Scalar(i.toDouble()), mask, Core.CMP_EQ)
            layerImages[i].copyTo(fused, mask)
        }
        
        return fused
    }

    private fun collapsePyramid(pyramid: List<Mat>): Mat {
        var current = pyramid.last()
        
        for (i in pyramid.size - 2 downTo 0) {
            val expanded = Mat()
            Imgproc.pyrUp(current, expanded, pyramid[i].size())
            Core.add(expanded, pyramid[i], current)
        }
        
        // Convert back to 8-bit
        val result = Mat()
        current.convertTo(result, CvType.CV_8U)
        return result
    }

    private fun saveResult(mat: Mat, burstStartWallTime: Long): File? {
        val folder = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DCIM), "Camera")
        if (!folder.exists()) folder.mkdirs()

        val sdf = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
        val filename = "ZeroBlur_${sdf.format(java.util.Date(burstStartWallTime))}.jpg"
        val file = File(folder, filename)

        try {
            val bitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(mat, bitmap)

            val out = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
            out.flush()
            out.close()

            // Scan file
            android.media.MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf("image/jpeg"),
                null
            )
            return file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save stacked image", e)
            return null
        }
    }

    private fun copyExif(srcUri: Uri, destFile: File) {
        try {
            val inputStream = context.contentResolver.openInputStream(srcUri)
            if (inputStream != null) {
                val oldExif = androidx.exifinterface.media.ExifInterface(inputStream)
                val newExif = androidx.exifinterface.media.ExifInterface(destFile)

                // Copy common tags
                val tags = arrayOf(
                    androidx.exifinterface.media.ExifInterface.TAG_APERTURE_VALUE,
                    androidx.exifinterface.media.ExifInterface.TAG_DATETIME,
                    androidx.exifinterface.media.ExifInterface.TAG_EXPOSURE_TIME,
                    androidx.exifinterface.media.ExifInterface.TAG_FLASH,
                    androidx.exifinterface.media.ExifInterface.TAG_FOCAL_LENGTH,
                    androidx.exifinterface.media.ExifInterface.TAG_GPS_ALTITUDE,
                    androidx.exifinterface.media.ExifInterface.TAG_GPS_ALTITUDE_REF,
                    androidx.exifinterface.media.ExifInterface.TAG_GPS_DATESTAMP,
                    androidx.exifinterface.media.ExifInterface.TAG_GPS_LATITUDE,
                    androidx.exifinterface.media.ExifInterface.TAG_GPS_LATITUDE_REF,
                    androidx.exifinterface.media.ExifInterface.TAG_GPS_LONGITUDE,
                    androidx.exifinterface.media.ExifInterface.TAG_GPS_LONGITUDE_REF,
                    androidx.exifinterface.media.ExifInterface.TAG_GPS_PROCESSING_METHOD,
                    androidx.exifinterface.media.ExifInterface.TAG_GPS_TIMESTAMP,
                    androidx.exifinterface.media.ExifInterface.TAG_ISO_SPEED,
                    androidx.exifinterface.media.ExifInterface.TAG_MAKE,
                    androidx.exifinterface.media.ExifInterface.TAG_MODEL,
                    androidx.exifinterface.media.ExifInterface.TAG_WHITE_BALANCE
                )

                for (tag in tags) {
                    val value = oldExif.getAttribute(tag)
                    if (value != null) {
                        newExif.setAttribute(tag, value)
                    }
                }
                newExif.saveAttributes()
                inputStream.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy EXIF", e)
        }
    }
}
