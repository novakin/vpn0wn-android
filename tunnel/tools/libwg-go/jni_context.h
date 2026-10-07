/* SPDX-License-Identifier: Apache-2.0 */
#ifndef WG_JNI_CONTEXT_H
#define WG_JNI_CONTEXT_H

#include <jni.h>

struct wg_context;
struct wg_context *wgCreateContext(JNIEnv *env, jobject service, jobject activation_thread);
int wgProtectSocket(struct wg_context *context, int fd);
int wgServiceActive(struct wg_context *context);
int wgFinishStartup(JNIEnv *env, struct wg_context *context);
void wgRethrowException(JNIEnv *env, struct wg_context *context);
void wgFreeContext(JNIEnv *env, struct wg_context *context);

#endif
