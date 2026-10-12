/* SPDX-License-Identifier: Apache-2.0 */
#include "jni_context.h"
#include <pthread.h>
#include <stdint.h>

struct callback_run {
	struct wg_context *context;
	int protected;
	int active;
};

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
