/* SPDX-License-Identifier: Apache-2.0 */
#ifndef WG_JNI_CONTEXT_H
#define WG_JNI_CONTEXT_H

#include <jni.h>

struct wg_context;
struct wg_capture;
struct wg_context *wgCreateContext(JNIEnv *env, jobject service, jobject activation_thread, jlong activation_id);
int wgProtectSocket(struct wg_context *context, int fd);
int wgServiceActive(struct wg_context *context);
int wgContextHealthy(struct wg_context *context);
void wgHandshakeObservation(struct wg_context *context, const char *peer, int event, int retry, const char *endpoint, jlong episode);
int wgCallerInterrupted(JNIEnv *env, jobject caller);
int wgFinishStartup(JNIEnv *env, struct wg_context *context);
int wgRethrowException(JNIEnv *env, struct wg_context *context);
void wgFreeContext(JNIEnv *env, struct wg_context *context);
struct wg_capture *wgCreateCapture(JNIEnv *env, jobject value);
int wgPrepareCapturedSocket(struct wg_context *context, struct wg_capture *capture, int fd);
int wgReportCapturedTransport(struct wg_context *context, struct wg_capture *capture, jlong generation, int ready);
int wgConfirmCapturedTransport(struct wg_context *context, struct wg_capture *capture, jlong generation);
void wgReleaseCapture(struct wg_context *context, struct wg_capture *capture);
void wgFreeCapture(JNIEnv *env, struct wg_capture *capture);

#endif
