package com.personatech.customannotations

import kotlin.annotation.AnnotationRetention.BINARY
import kotlin.annotation.AnnotationTarget.CLASS


/**
 * Identifies Retrofit NetworkService with its scope.
 * Corosponding NetworkServiceProvider will be automatically generated based on scope.
 * Scope can be "loggedIn" or "loggedOut", generator will fail for any other scope
 */
@Target(CLASS)
@Retention(BINARY)
annotation class NetworkService(val scope: String)
