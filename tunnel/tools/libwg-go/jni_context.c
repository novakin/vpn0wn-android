/* SPDX-License-Identifier: Apache-2.0 */
#include "jni_context.h"
#include <pthread.h>
#include <stdlib.h>

struct wg_context {
	JavaVM *vm;
	jobject service;
	jobject activation_thread;
	jmethodID protect;
	jmethodID active;
	jmethodID observation;
	jlong activation_id;
	jthrowable exception;
	int failed;
	pthread_mutex_t mutex;
	struct wg_capture *pending_releases;
};

struct wg_capture {
	jobject value;
	jmethodID prepare;
	jmethodID report;
	jmethodID confirm;
	struct wg_capture *next;
};

static JNIEnv *callbackEnv(struct wg_context *context, int *attached)
{
	JNIEnv *env = NULL;
	*attached = 0;
	jint result = (*context->vm)->GetEnv(context->vm, (void **)&env, JNI_VERSION_1_6);
	if (result == JNI_EDETACHED) {
		/* Android and host JDK headers use different AttachCurrentThread output types. */
#ifdef __ANDROID__
		result = (*context->vm)->AttachCurrentThread(context->vm, &env, NULL);
#else
		result = (*context->vm)->AttachCurrentThread(context->vm, (void **)&env, NULL);
#endif
		if (result != JNI_OK)
			return NULL;
		*attached = 1;
	} else if (result != JNI_OK) {
		return NULL;
	}
	return env;
}

static int captureException(JNIEnv *env, struct wg_context *context)
{
	if (!(*env)->ExceptionCheck(env))
		return 0;
	jthrowable exception = (*env)->ExceptionOccurred(env);
	(*env)->ExceptionClear(env);
	if (!context->exception)
		context->exception = (*env)->NewGlobalRef(env, exception);
	(*env)->DeleteLocalRef(env, exception);
	/* A native-created thread cannot carry a pending exception back to the Java caller. */
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionClear(env);
	context->failed = 1;
	return 1;
}

struct wg_capture *wgCreateCapture(JNIEnv *env, jobject value)
{
	struct wg_capture *capture = calloc(1, sizeof(*capture));
	if (!capture)
		return NULL;
	capture->value = (*env)->NewGlobalRef(env, value);
	if (!capture->value) {
		free(capture);
		return NULL;
	}
	jclass value_class = (*env)->GetObjectClass(env, value);
	if (capture->value && value_class) {
		capture->prepare = (*env)->GetMethodID(env, value_class, "prepare", "(I)Z");
		if (capture->prepare)
			capture->report = (*env)->GetMethodID(env, value_class, "report", "(JZ)Z");
		if (capture->report)
			capture->confirm = (*env)->GetMethodID(env, value_class, "confirm", "(J)Z");
	}
	if (value_class)
		(*env)->DeleteLocalRef(env, value_class);
	if (!capture->value || !capture->prepare || !capture->report || !capture->confirm) {
		if (capture->value)
			(*env)->DeleteGlobalRef(env, capture->value);
		free(capture);
		return NULL;
	}
	return capture;
}

static int capturedCallback(struct wg_context *context, struct wg_capture *capture, int fd, jlong generation, int report, int ready)
{
	pthread_mutex_lock(&context->mutex);
	int attached;
	JNIEnv *env = callbackEnv(context, &attached);
	int result = 0;
	if (!env) {
		context->failed = 1;
	} else if (!context->failed) {
		result = (*env)->CallBooleanMethod(env, context->service, context->active, context->activation_thread);
		if (captureException(env, context))
			result = 0;
		if (result) {
			if (report == 2)
				result = (*env)->CallBooleanMethod(env, capture->value, capture->confirm, generation);
			else if (report)
				result = (*env)->CallBooleanMethod(env, capture->value, capture->report, generation, (jboolean)ready);
			else
				result = (*env)->CallBooleanMethod(env, capture->value, capture->prepare, (jint)fd);
			if (captureException(env, context))
				result = 0;
		}
	}
	if (attached)
		(*context->vm)->DetachCurrentThread(context->vm);
	pthread_mutex_unlock(&context->mutex);
	return result;
}

int wgPrepareCapturedSocket(struct wg_context *context, struct wg_capture *capture, int fd)
{
	return capturedCallback(context, capture, fd, 0, 0, 0);
}

int wgReportCapturedTransport(struct wg_context *context, struct wg_capture *capture, jlong generation, int ready)
{
	return capturedCallback(context, capture, -1, generation, 1, ready);
}

int wgConfirmCapturedTransport(struct wg_context *context, struct wg_capture *capture, jlong generation)
{
	return capturedCallback(context, capture, -1, generation, 2, 1);
}

void wgReleaseCapture(struct wg_context *context, struct wg_capture *capture)
{
	pthread_mutex_lock(&context->mutex);
	int attached;
	JNIEnv *env = callbackEnv(context, &attached);
	if (env) {
		(*env)->DeleteGlobalRef(env, capture->value);
		free(capture);
	} else {
		context->failed = 1;
		capture->next = context->pending_releases;
		context->pending_releases = capture;
	}
	if (attached)
		(*context->vm)->DetachCurrentThread(context->vm);
	pthread_mutex_unlock(&context->mutex);
}

void wgFreeCapture(JNIEnv *env, struct wg_capture *capture)
{
	(*env)->DeleteGlobalRef(env, capture->value);
	free(capture);
}

struct wg_context *wgCreateContext(JNIEnv *env, jobject service, jobject activation_thread, jlong activation_id)
{
	struct wg_context *context = calloc(1, sizeof(*context));
	if (!context)
		return NULL;
	if (pthread_mutex_init(&context->mutex, NULL) != 0) {
		free(context);
		return NULL;
	}
	if ((*env)->GetJavaVM(env, &context->vm) != JNI_OK)
		goto failed;
	context->service = (*env)->NewGlobalRef(env, service);
	if (!context->service)
		goto failed;
	context->activation_id = activation_id;
	context->activation_thread = (*env)->NewGlobalRef(env, activation_thread);
	if (!context->activation_thread)
		goto failed;
	jclass service_class = (*env)->GetObjectClass(env, service);
	if (!service_class)
		goto failed;
	context->protect = (*env)->GetMethodID(env, service_class, "protectNativeSocket", "(I)Z");
	if (context->protect)
		context->active = (*env)->GetMethodID(env, service_class, "isNativeServiceActive", "(Ljava/lang/Thread;)Z");
	if (context->active)
		context->observation = (*env)->GetMethodID(env, service_class, "onNativeHandshakeObservation", "(J[BIZLjava/lang/String;J)V");
	(*env)->DeleteLocalRef(env, service_class);
	if (!context->protect || !context->active || !context->observation)
		goto failed;
	return context;
failed:
	wgFreeContext(env, context);
	return NULL;
}

static int callback(struct wg_context *context, int fd, int protect)
{
	pthread_mutex_lock(&context->mutex);
	int attached;
	JNIEnv *env = callbackEnv(context, &attached);
	int result = 0;
	if (!env) {
		context->failed = 1;
	} else if (!context->failed) {
		result = (*env)->CallBooleanMethod(env, context->service, context->active, context->activation_thread);
		if (captureException(env, context))
			result = 0;
		if (result && protect) {
			result = (*env)->CallBooleanMethod(env, context->service, context->protect, (jint)fd);
			if (captureException(env, context))
				result = 0;
		}
	}
	if (attached)
		(*context->vm)->DetachCurrentThread(context->vm);
	pthread_mutex_unlock(&context->mutex);
	return result;
}

int wgProtectSocket(struct wg_context *context, int fd)
{
	return callback(context, fd, 1);
}

int wgServiceActive(struct wg_context *context)
{
	return callback(context, -1, 0);
}

int wgContextHealthy(struct wg_context *context)
{
	pthread_mutex_lock(&context->mutex);
	int healthy = !context->failed;
	pthread_mutex_unlock(&context->mutex);
	return healthy;
}

void wgHandshakeObservation(struct wg_context *context, const char *peer, int event, int retry, const char *endpoint, jlong episode)
{
	pthread_mutex_lock(&context->mutex);
	int attached;
	JNIEnv *env = callbackEnv(context, &attached);
	if (!env) {
		context->failed = 1;
	} else if (!context->failed) {
		int active = (*env)->CallBooleanMethod(env, context->service, context->active, context->activation_thread);
		if (!captureException(env, context) && active) {
			jbyteArray key = (*env)->NewByteArray(env, 32);
			if (key)
				(*env)->SetByteArrayRegion(env, key, 0, 32, (const jbyte *)peer);
			jstring address = NULL;
			if (!captureException(env, context))
				address = (*env)->NewStringUTF(env, endpoint);
			if (!captureException(env, context) && key && address) {
				(*env)->CallVoidMethod(env, context->service, context->observation, context->activation_id,
					key, (jint)event, (jboolean)(retry != 0), address, episode);
				captureException(env, context);
			}
			if (address)
				(*env)->DeleteLocalRef(env, address);
			if (key)
				(*env)->DeleteLocalRef(env, key);
		}
	}
	if (attached)
		(*context->vm)->DetachCurrentThread(context->vm);
	pthread_mutex_unlock(&context->mutex);
}

int wgCallerInterrupted(JNIEnv *env, jobject caller)
{
	jclass caller_class = (*env)->GetObjectClass(env, caller);
	if (!caller_class)
		return -1;
	jmethodID interrupted = (*env)->GetMethodID(env, caller_class, "isInterrupted", "()Z");
	(*env)->DeleteLocalRef(env, caller_class);
	if (!interrupted)
		return -1;
	int result = (*env)->CallBooleanMethod(env, caller, interrupted);
	if ((*env)->ExceptionCheck(env))
		return -1;
	return result;
}

int wgFinishStartup(JNIEnv *env, struct wg_context *context)
{
	pthread_mutex_lock(&context->mutex);
	if (context->activation_thread) {
		(*env)->DeleteGlobalRef(env, context->activation_thread);
		context->activation_thread = NULL;
	}
	int active = !context->failed;
	pthread_mutex_unlock(&context->mutex);
	return active;
}

int wgRethrowException(JNIEnv *env, struct wg_context *context)
{
	pthread_mutex_lock(&context->mutex);
	int raised = 0;
	if (context->exception) {
		(*env)->Throw(env, context->exception);
		(*env)->DeleteGlobalRef(env, context->exception);
		context->exception = NULL;
		raised = 1;
	}
	pthread_mutex_unlock(&context->mutex);
	return raised;
}

void wgFreeContext(JNIEnv *env, struct wg_context *context)
{
	while (context->pending_releases) {
		struct wg_capture *capture = context->pending_releases;
		context->pending_releases = capture->next;
		wgFreeCapture(env, capture);
	}
	if (context->service)
		(*env)->DeleteGlobalRef(env, context->service);
	if (context->activation_thread)
		(*env)->DeleteGlobalRef(env, context->activation_thread);
	if (context->exception)
		(*env)->DeleteGlobalRef(env, context->exception);
	pthread_mutex_destroy(&context->mutex);
	free(context);
}
