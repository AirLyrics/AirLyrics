package com.andsi.airlyrics.test

/** Tests excluded from the fast PR and push suite. */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class ExtendedInstrumentationTest

/** Real rotations and changes to device-wide display or input settings. */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class SystemStressTest

/** Tests that assert the contents of committed hardware frames. */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class HardwareFrameTest
