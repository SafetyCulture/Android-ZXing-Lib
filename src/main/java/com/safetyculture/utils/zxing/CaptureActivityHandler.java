/*
 * Copyright (C) 2008 ZXing authors
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

package com.safetyculture.utils.zxing;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.DecodeHintType;
import com.google.zxing.Result;
import com.safetyculture.utils.zxing.camera.CameraManager;

import java.util.Collection;
import java.util.Map;

/**
 * This class handles all the messaging which comprises the state machine for
 * capture.
 *
 * @author dswitkin@google.com (Daniel Switkin)
 */
public final class CaptureActivityHandler extends Handler
{
	private static final String TAG = CaptureActivityHandler.class.getSimpleName();

	private final CameraManager cameraManager;
	private final CaptureActivity activity;
	private final DecodeThread decodeThread;
	private State state;

	private enum State
	{
		PREVIEW,
		SUCCESS,
		DONE
	}

	CaptureActivityHandler(CaptureActivity activity,
						   Collection<BarcodeFormat> decodeFormats,
						   Map<DecodeHintType, ?> baseHints,
						   String characterSet,
						   CameraManager cameraManager)
	{
		this.activity = activity;
		decodeThread = new DecodeThread(activity, decodeFormats, baseHints, characterSet,
				new ViewfinderResultPointCallback(activity.getViewfinderView()));
		decodeThread.start();
		state = State.SUCCESS;

		// Start ourselves capturing previews and decoding.
		this.cameraManager = cameraManager;
		cameraManager.startPreview();
		restartPreviewAndDecode();
	}

	@Override
	public void handleMessage(Message message)
	{
		if(message.what == R.id.zxinglib_decode_succeeded)
		{
			state = State.SUCCESS;
			Bundle bundle = message.getData();
			Bitmap barcode = null;
			if(bundle != null)
			{
				byte[] compressedBitmap = bundle.getByteArray(DecodeThread.BARCODE_BITMAP);
				if(compressedBitmap != null)
				{
					barcode = BitmapFactory.decodeByteArray(compressedBitmap, 0, compressedBitmap.length, null);
					// Mutable copy:
					barcode = barcode.copy(Bitmap.Config.ARGB_8888, true);
				}
			}
			activity.handleDecode((Result) message.obj, barcode, 1);
		}
		else if(message.what == R.id.zxinglib_decode_failed)
		{
			// We're decoding as fast as possible, so when one decode fails, start another.
			state = State.PREVIEW;
			cameraManager.requestPreviewFrame(decodeThread.getHandler(),
					R.id.zxinglib_decode);
		}
		else if(message.what == R.id.zxinglib_return_scan_result)
		{
			Log.d(TAG, "Got return scan result message");
			activity.setResult(Activity.RESULT_OK, (Intent) message.obj);
			activity.finish();
		}
	}

	public void quitSynchronously()
	{
		state = State.DONE;
		cameraManager.stopPreview();
		Message quit = Message.obtain(decodeThread.getHandler(), R.id.zxinglib_quit);
		quit.sendToTarget();
		try
		{
			decodeThread.join();
		}
		catch(InterruptedException e)
		{
			// continue
		}

		// Be absolutely sure we don't send any queued up messages
		removeMessages(R.id.zxinglib_decode_succeeded);
		removeMessages(R.id.zxinglib_decode_failed);
	}

	private void restartPreviewAndDecode()
	{
		if(state == State.SUCCESS)
		{
			state = State.PREVIEW;
			cameraManager.requestPreviewFrame(decodeThread.getHandler(), R.id.zxinglib_decode);
			activity.drawViewfinder();
		}
	}
}
