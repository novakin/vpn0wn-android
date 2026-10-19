/* SPDX-License-Identifier: Apache-2.0
 *
 * Copyright © 2017-2021 Jason A. Donenfeld <Jason@zx2c4.com>. All Rights Reserved.
 * Modified for vpn0wn.
 */

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include "jni_context.h"

struct go_string { const char *str; long n; };
extern int wgTurnOn(struct go_string ifname, int tun_fd, struct go_string settings, struct wg_context *context, JNIEnv *env, struct wg_capture *capture, int amnezia);
extern void wgTurnOff(int handle, JNIEnv *env);
extern char *wgGetConfig(int handle, JNIEnv *env);
extern int wgRebind(int handle, JNIEnv *env);
extern jlong wgTransport(int handle, jlong expected, struct wg_capture *capture, int available, JNIEnv *env);
extern jlong wgTransportSnapshot(int handle);
extern int wgRecover(int handle, const char *peer, jlong episode, struct go_string endpoint, JNIEnv *env, jobject caller);
extern char *wgVersion();

static jint turnOn(JNIEnv *env, jstring ifname, jint tun_fd, jstring settings, jobject service, jobject activation_thread, jlong activation_id, jobject capture_value, int amnezia)
{
	struct wg_context *context = NULL;
	const char *ifname_str = (*env)->GetStringUTFChars(env, ifname, 0);
	if (!ifname_str)
		goto failed;
	size_t ifname_len = (*env)->GetStringUTFLength(env, ifname);
	const char *settings_str = (*env)->GetStringUTFChars(env, settings, 0);
	if (!settings_str) {
		(*env)->ReleaseStringUTFChars(env, ifname, ifname_str);
		goto failed;
	}
	size_t settings_len = (*env)->GetStringUTFLength(env, settings);
	context = wgCreateContext(env, service, activation_thread, activation_id);
	if (!context) {
		(*env)->ReleaseStringUTFChars(env, ifname, ifname_str);
		(*env)->ReleaseStringUTFChars(env, settings, settings_str);
		goto failed;
	}
	struct wg_capture *capture = capture_value ? wgCreateCapture(env, capture_value) : NULL;
	if (capture_value && !capture) {
		wgFreeContext(env, context);
		(*env)->ReleaseStringUTFChars(env, ifname, ifname_str);
		(*env)->ReleaseStringUTFChars(env, settings, settings_str);
		goto failed;
	}
	int ret = wgTurnOn((struct go_string){
		.str = ifname_str,
		.n = ifname_len
	}, tun_fd, (struct go_string){
		.str = settings_str,
		.n = settings_len
	}, context, env, capture, amnezia);
	(*env)->ReleaseStringUTFChars(env, ifname, ifname_str);
	(*env)->ReleaseStringUTFChars(env, settings, settings_str);
	return ret;
failed:
	close(tun_fd);
	return -1;
}

JNIEXPORT jint JNICALL Java_com_vpn0wn_android_backend_GoBackend_wgTurnOn(JNIEnv *env, jclass c, jstring ifname, jint tun_fd, jstring settings, jobject service, jobject activation_thread, jlong activation_id, jobject capture_value)
{
	return turnOn(env, ifname, tun_fd, settings, service, activation_thread, activation_id, capture_value, 0);
}

JNIEXPORT jint JNICALL Java_com_vpn0wn_android_backend_GoBackend_awgTurnOn(JNIEnv *env, jclass c, jstring ifname, jint tun_fd, jstring settings, jobject service, jobject activation_thread, jlong activation_id, jobject capture_value)
{
	return turnOn(env, ifname, tun_fd, settings, service, activation_thread, activation_id, capture_value, 1);
}

JNIEXPORT void JNICALL Java_com_vpn0wn_android_backend_GoBackend_wgTurnOff(JNIEnv *env, jclass c, jint handle)
{
	wgTurnOff(handle, env);
}

JNIEXPORT jint JNICALL Java_com_vpn0wn_android_backend_GoBackend_wgRebind(JNIEnv *env, jclass c, jint handle)
{
	return wgRebind(handle, env);
}

JNIEXPORT jlong JNICALL Java_com_vpn0wn_android_backend_GoBackend_wgTransport(JNIEnv *env, jclass c, jint handle, jlong expected, jobject capture_value, jboolean available)
{
	struct wg_capture *capture = wgCreateCapture(env, capture_value);
	if (!capture)
		return 131;
	return wgTransport(handle, expected, capture, available, env);
}

JNIEXPORT jlong JNICALL Java_com_vpn0wn_android_backend_GoBackend_wgTransportSnapshot(JNIEnv *env, jclass c, jint handle)
{
	return wgTransportSnapshot(handle);
}

JNIEXPORT jint JNICALL Java_com_vpn0wn_android_backend_GoBackend_wgRecover(JNIEnv *env, jclass c, jint handle, jbyteArray peer, jlong episode, jstring endpoint, jobject caller)
{
	if ((*env)->GetArrayLength(env, peer) != 32)
		return 2;
	jbyte public_key[32];
	(*env)->GetByteArrayRegion(env, peer, 0, 32, public_key);
	if ((*env)->ExceptionCheck(env))
		return 3;
	const char *endpoint_str = (*env)->GetStringUTFChars(env, endpoint, 0);
	if (!endpoint_str)
		return 3;
	size_t endpoint_len = (*env)->GetStringUTFLength(env, endpoint);
	int result = wgRecover(handle, (const char *)public_key, episode, (struct go_string){endpoint_str, endpoint_len}, env, caller);
	(*env)->ReleaseStringUTFChars(env, endpoint, endpoint_str);
	return result;
}

JNIEXPORT jstring JNICALL Java_com_vpn0wn_android_backend_GoBackend_wgGetConfig(JNIEnv *env, jclass c, jint handle)
{
	jstring ret;
	char *config = wgGetConfig(handle, env);
	if (!config)
		return NULL;
	ret = (*env)->NewStringUTF(env, config);
	free(config);
	return ret;
}

JNIEXPORT jstring JNICALL Java_com_vpn0wn_android_backend_GoBackend_wgVersion(JNIEnv *env, jclass c)
{
	jstring ret;
	char *version = wgVersion();
	if (!version)
		return NULL;
	ret = (*env)->NewStringUTF(env, version);
	free(version);
	return ret;
}
