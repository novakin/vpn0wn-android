/* SPDX-License-Identifier: Apache-2.0
 *
 * Copyright © 2017-2021 Jason A. Donenfeld <Jason@zx2c4.com>. All Rights Reserved.
 */

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include "jni_context.h"

struct go_string { const char *str; long n; };
extern int wgTurnOn(struct go_string ifname, int tun_fd, struct go_string settings, struct wg_context *context, JNIEnv *env);
extern void wgTurnOff(int handle, JNIEnv *env);
extern char *wgGetConfig(int handle, JNIEnv *env);
extern char *wgVersion();

JNIEXPORT jint JNICALL Java_com_wireguard_android_backend_GoBackend_wgTurnOn(JNIEnv *env, jclass c, jstring ifname, jint tun_fd, jstring settings, jobject service, jobject activation_thread, jlong activation_id)
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
	int ret = wgTurnOn((struct go_string){
		.str = ifname_str,
		.n = ifname_len
	}, tun_fd, (struct go_string){
		.str = settings_str,
		.n = settings_len
	}, context, env);
	(*env)->ReleaseStringUTFChars(env, ifname, ifname_str);
	(*env)->ReleaseStringUTFChars(env, settings, settings_str);
	return ret;
failed:
	close(tun_fd);
	return -1;
}

JNIEXPORT void JNICALL Java_com_wireguard_android_backend_GoBackend_wgTurnOff(JNIEnv *env, jclass c, jint handle)
{
	wgTurnOff(handle, env);
}

JNIEXPORT jstring JNICALL Java_com_wireguard_android_backend_GoBackend_wgGetConfig(JNIEnv *env, jclass c, jint handle)
{
	jstring ret;
	char *config = wgGetConfig(handle, env);
	if (!config)
		return NULL;
	ret = (*env)->NewStringUTF(env, config);
	free(config);
	return ret;
}

JNIEXPORT jstring JNICALL Java_com_wireguard_android_backend_GoBackend_wgVersion(JNIEnv *env, jclass c)
{
	jstring ret;
	char *version = wgVersion();
	if (!version)
		return NULL;
	ret = (*env)->NewStringUTF(env, version);
	free(version);
	return ret;
}
