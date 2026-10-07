/* SPDX-License-Identifier: Apache-2.0 */
#include "jni_context.h"
#include <pthread.h>

struct callback_run {
	struct wg_context *context;
	int protected;
	int active;
};

static void *run_callback(void *argument)
{
	struct callback_run *run = argument;
	run->protected = wgProtectSocket(run->context, 42);
	run->active = wgServiceActive(run->context);
	return NULL;
}

JNIEXPORT jint JNICALL Java_WgContextTest_exercise(JNIEnv *env, jclass type, jobject service, jobject thread)
{
	struct wg_context *context = wgCreateContext(env, service, thread);
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
