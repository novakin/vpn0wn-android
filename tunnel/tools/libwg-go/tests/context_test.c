/* SPDX-License-Identifier: Apache-2.0 */
#include "jni_context.h"
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>

struct callback_run {
	struct wg_context *context;
	int protected;
	int active;
};

struct capture_run {
	struct wg_context *context;
	struct wg_capture *capture;
	int result;
};

static void *run_captured_callback(void *argument)
{
	struct capture_run *run = argument;
	run->result = wgPrepareCapturedSocket(run->context, run->capture, 42);
	run->result |= wgReportCapturedTransport(run->context, run->capture, 91, 1) << 1;
	run->result |= wgConfirmCapturedTransport(run->context, run->capture, 91) << 2;
	run->result |= wgReportCapturedTransport(run->context, run->capture, 92, 0) << 3;
	run->result |= wgContextHealthy(run->context) << 4;
	wgReleaseCapture(run->context, run->capture);
	return NULL;
}

JNIEXPORT jint JNICALL Java_WgContextTest_exerciseCaptured(JNIEnv *env, jclass type, jobject service, jobject thread, jobject capture)
{
	struct wg_context *context = wgCreateContext(env, service, thread, 73);
	if (!context)
		return -1;
	struct wg_capture *captured = wgCreateCapture(env, capture);
	if (!captured) {
		wgFreeContext(env, context);
		return -1;
	}
	struct capture_run run = { .context = context, .capture = captured };
	pthread_t worker;
	if (pthread_create(&worker, NULL, run_captured_callback, &run) != 0) {
		wgFreeCapture(env, captured);
		wgFreeContext(env, context);
		return -2;
	}
	pthread_join(worker, NULL);
	wgRethrowException(env, context);
	wgFreeContext(env, context);
	return run.result;
}

JNIEXPORT jlong JNICALL Java_WgContextTest_retainCapture(JNIEnv *env, jclass type, jobject service, jobject thread, jobject value)
{
	struct capture_run *run = calloc(1, sizeof(*run));
	if (!run)
		return 0;
	run->context = wgCreateContext(env, service, thread, 73);
	run->capture = wgCreateCapture(env, value);
	if (!run->context || !run->capture) {
		if (run->capture)
			wgFreeCapture(env, run->capture);
		if (run->context)
			wgFreeContext(env, run->context);
		free(run);
		return 0;
	}
	return (jlong)(intptr_t)run;
}

static void *release_captured_callback(void *argument)
{
	struct capture_run *run = argument;
	wgReleaseCapture(run->context, run->capture);
	return NULL;
}

JNIEXPORT void JNICALL Java_WgContextTest_freeCapture(JNIEnv *env, jclass type, jlong handle)
{
	struct capture_run *run = (struct capture_run *)(intptr_t)handle;
	pthread_t worker;
	if (pthread_create(&worker, NULL, release_captured_callback, run) != 0)
		wgFreeCapture(env, run->capture);
	else
		pthread_join(worker, NULL);
	wgRethrowException(env, run->context);
	wgFreeContext(env, run->context);
	free(run);
}

JNIEXPORT jboolean JNICALL Java_WgContextTest_callerInterrupted(JNIEnv *env, jclass type, jobject caller)
{
	return wgCallerInterrupted(env, caller) != 0;
}

static void *run_callback(void *argument)
{
	struct callback_run *run = argument;
	run->protected = wgProtectSocket(run->context, 42);
	run->active = wgServiceActive(run->context);
	char peer[32] = { 9 };
	wgHandshakeObservation(run->context, peer, 3, 1, "[::1]:51820", 91);
	return NULL;
}

JNIEXPORT jint JNICALL Java_WgContextTest_exercise(JNIEnv *env, jclass type, jobject service, jobject thread)
{
	struct wg_context *context = wgCreateContext(env, service, thread, 73);
	if (!context)
		return -1;
	struct callback_run run = { .context = context };
	pthread_t worker;
	int result = -2;
	if (pthread_create(&worker, NULL, run_callback, &run) != 0)
		goto done;
	pthread_join(worker, NULL);
	result = run.protected | (run.active << 1);
	if (wgFinishStartup(env, context))
		result |= 4;
	if (pthread_create(&worker, NULL, run_callback, &run) != 0) {
		result = -2;
		goto done;
	}
	pthread_join(worker, NULL);
	result |= (run.protected << 3) | (run.active << 4);
done:
	wgRethrowException(env, context);
	wgFreeContext(env, context);
	return result;
}

JNIEXPORT jlong JNICALL Java_WgContextTest_createRetained(JNIEnv *env, jclass type, jobject service, jobject thread)
{
	struct wg_context *context = wgCreateContext(env, service, thread, 73);
	if (!context)
		return 0;
	if (!wgFinishStartup(env, context)) {
		wgFreeContext(env, context);
		return 0;
	}
	return (jlong)(intptr_t)context;
}

JNIEXPORT jint JNICALL Java_WgContextTest_checkRetained(JNIEnv *env, jclass type, jlong handle)
{
	struct wg_context *context = (struct wg_context *)(intptr_t)handle;
	struct callback_run run = { .context = context };
	run_callback(&run);
	wgRethrowException(env, context);
	return run.protected | (run.active << 1);
}

JNIEXPORT void JNICALL Java_WgContextTest_closeRetained(JNIEnv *env, jclass type, jlong handle)
{
	wgFreeContext(env, (struct wg_context *)(intptr_t)handle);
}
