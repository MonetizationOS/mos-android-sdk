package com.monetizationos.sdk

/** Plumbing between mos-core and mos-android, not for apps. It may change in any release. */
@RequiresOptIn(message = "Internal to the MonetizationOS SDK.", level = RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.CONSTRUCTOR, AnnotationTarget.FUNCTION)
public annotation class InternalMosApi
