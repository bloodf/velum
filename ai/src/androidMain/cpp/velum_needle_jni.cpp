#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <vector>
#include "needle.h"

extern "C" {

JNIEXPORT jboolean JNICALL
Java_dev_velum_ai_needle_NeedleNative_nativeLoadCact(
    JNIEnv* env, jclass, jbyteArray cactBytes) {
  if (!cactBytes) return JNI_FALSE;
  jsize n = env->GetArrayLength(cactBytes);
  jbyte* bytes = env->GetByteArrayElements(cactBytes, nullptr);
  if (!bytes) return JNI_FALSE;
  int rc = needle_load(reinterpret_cast<const unsigned char*>(bytes),
                       static_cast<unsigned long long>(n));
  env->ReleaseByteArrayElements(cactBytes, bytes, JNI_ABORT);
  return rc == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_dev_velum_ai_needle_NeedleNative_nativeLastError(JNIEnv* env, jclass) {
  const char* err = needle_last_error();
  return env->NewStringUTF(err ? err : "");
}

JNIEXPORT jstring JNICALL
Java_dev_velum_ai_needle_NeedleNative_nativeTranscribe(
    JNIEnv* env, jclass,
    jfloatArray pcm, jstring language, jboolean wordTimestamps) {
  if (!pcm) return nullptr;
  jsize samples = env->GetArrayLength(pcm);
  jfloat* data = env->GetFloatArrayElements(pcm, nullptr);
  if (!data) return nullptr;
  const char* lang = language ? env->GetStringUTFChars(language, nullptr) : nullptr;
  std::vector<char> out(1 << 20);
  int rc = needle_transcribe(
      data, static_cast<int>(samples),
      lang, nullptr,
      wordTimestamps ? 1 : 0,
      out.data(), static_cast<int>(out.size()));
  if (lang) env->ReleaseStringUTFChars(language, lang);
  env->ReleaseFloatArrayElements(pcm, data, JNI_ABORT);
  if (rc < 0) return nullptr;
  return env->NewStringUTF(out.data());
}

}  // extern "C"
