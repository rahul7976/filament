/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.filament.litcube

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.support.v4.content.ContextCompat
import android.util.Log
import android.util.Size
import android.view.Surface

import android.Manifest
import android.content.res.Configuration
import android.graphics.Rect

import com.google.android.filament.*

import java.lang.Long.signum

import java.util.Collections
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.Comparator

/**
 * Toy class that handles all Camera2 interaction and sets the "aspectRatio" and "videoTexture" parameters on the given Filament material.
 */
class CameraHelper(val activity: Activity, private val filamentEngine: Engine, private val filamentMaterial: MaterialInstance) {
    private lateinit var cameraId: String
    private var captureSession: CameraCaptureSession? = null
    private var cameraDevice: CameraDevice? = null
    private val cameraOpenCloseLock = Semaphore(1)
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var imageReader: ImageReader? = null
    private lateinit var captureRequest: CaptureRequest
    private var resolution = Size(640, 480)

    private val stateCallback = object : CameraDevice.StateCallback() {

        override fun onOpened(cameraDevice: CameraDevice) {
            cameraOpenCloseLock.release()
            this@CameraHelper.cameraDevice = cameraDevice
             createCaptureSession()
        }

        override fun onDisconnected(cameraDevice: CameraDevice) {
            cameraOpenCloseLock.release()
            cameraDevice.close()
            this@CameraHelper.cameraDevice = null
        }

        override fun onError(cameraDevice: CameraDevice, error: Int) {
            onDisconnected(cameraDevice)
            this@CameraHelper.activity.finish()
        }
    }

    fun openCamera() {
        val permission = ContextCompat.checkSelfPermission(this.activity, Manifest.permission.CAMERA)
        setUpCameraOutputs()
        if (permission != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(arrayOf(Manifest.permission.CAMERA), kRequestCameraPermission)
            return
        }
        val manager = this.activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
            throw RuntimeException("Time out waiting to lock camera opening.")
        }
        manager.openCamera(cameraId, stateCallback, backgroundHandler)
    }

    fun onResume() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread?.looper!!)
    }

    fun onPause() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(kLogTag, e.toString())
        }
    }

    fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray): Boolean {
        if (requestCode == kRequestCameraPermission) {
            if (grantResults.size != 1 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Log.e(kLogTag, "Unable to obtain camera position.")
            }
            return true
        }
        return false
    }

    private fun setUpCameraOutputs() {
        val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            for (cameraId in manager.cameraIdList) {
                val characteristics = manager.getCameraCharacteristics(cameraId)
                val cameraDirection = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (cameraDirection != null && cameraDirection == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue
                }

                this.cameraId = cameraId
                Log.i(kLogTag, "Selected camera: $cameraId")

                val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
                resolution = map.getOutputSizes(SurfaceTexture::class.java)[0]
                Log.i(kLogTag, "Highest resolution: $resolution")

                //val resolution = Collections.max(sizes, CompareSizesByArea())// NOTE: any ImageFormat other than JPEG results in an empty list
                //imageReader = ImageReader.newInstance(resolution.width, resolution.height, ImageFormat.YUV_420_888, kMaxImages)
                //Log.i(kLogTag, "Created ImageReader: $imageReader ${resolution.width} x ${resolution.height}")
                return
            }
        } catch (e: CameraAccessException) {
            Log.e(kLogTag, e.toString())
        } catch (e: NullPointerException) {
            Log.e(kLogTag, "Camera2 API is not supported on this device.")
        }

    }

    private fun createCaptureSession() {
        // Create the Android surface that will hold the camera image.
        val surfaceTexture = SurfaceTexture(0)
        surfaceTexture.setDefaultBufferSize(resolution.width, resolution.height)
        surfaceTexture.detachFromGLContext()
        val surface = Surface(surfaceTexture)

        // Create the Filament Stream object that gets bound to the Texture.
        val filamentStream = Stream.Builder()
                .stream(surfaceTexture)
                .build(filamentEngine)

        // Create the Filament Texture and Sampler objects.
        val filamentTexture = Texture.Builder()
                .sampler(Texture.Sampler.SAMPLER_EXTERNAL)
                .format(Texture.InternalFormat.RGB8)
                .build(filamentEngine)

        val sampler = TextureSampler(TextureSampler.MinFilter.LINEAR, TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.CLAMP_TO_EDGE)

        var aspectRatio = resolution.width.toFloat() / resolution.height.toFloat()

        if (activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            aspectRatio = -aspectRatio
        }

            // Connect the Stream to the Texture and the Texture to the MaterialInstance.
        filamentTexture.setExternalStream(filamentEngine, filamentStream)
        filamentMaterial.setParameter("videoTexture", filamentTexture, sampler)
        filamentMaterial.setParameter("aspectRatio", aspectRatio)

        // Start the capture session. You could also use TEMPLATE_PREVIEW here.
        val captureRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        captureRequestBuilder.addTarget(surface)

        //val surfaces = listOf(surface, imageReader?.surface)
        val surfaces = listOf(surface)

        cameraDevice?.createCaptureSession(surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                        if (cameraDevice == null) return
                        captureSession = cameraCaptureSession
                        captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        captureRequest = captureRequestBuilder.build()
                        captureSession!!.setRepeatingRequest(captureRequest, null, backgroundHandler)
                        Log.i(kLogTag, "Created CaptureRequest.")
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(kLogTag, "onConfigureFailed")
                    }
                }, null)
    }

    internal class CompareSizesByArea : Comparator<Size> {
        override fun compare(lhs: Size, rhs: Size) = signum(lhs.width.toLong() * lhs.height - rhs.width.toLong() * rhs.height)
    }

    companion object {
        private const val kLogTag = "CameraHelper"
        private const val kMaxImages = 16
        private const val kRequestCameraPermission = 1
    }

}
