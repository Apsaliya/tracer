package com.personatech.customannotations

import kotlin.annotation.AnnotationRetention.RUNTIME
import kotlin.annotation.AnnotationTarget.FUNCTION

/**
 * Identifies Video Module entry point method in MainNavigationRoutesImpl.
 * This is used to do instrumentation in compiler plugin which fills the right intent based on intentTyp.
 * intentType can only be "videoMeeting" or "techcheck. Plugin will fail for any other input.
 * Plugin will change method body to return correct intent based on inputType.
 * Currently plugin only supports to change method body which returns intent for video module
 */
@Target(FUNCTION)
@Retention(RUNTIME)
annotation class VideoModuleEntryPoint(
  val intentType: String
)
