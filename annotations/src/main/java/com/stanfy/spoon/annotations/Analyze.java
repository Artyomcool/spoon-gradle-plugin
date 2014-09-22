package com.stanfy.spoon.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
public @interface Analyze {
	boolean clearAllTests() default false;
	boolean forceStopAllTests() default false;
	boolean respectTestsOrder() default false;
}
