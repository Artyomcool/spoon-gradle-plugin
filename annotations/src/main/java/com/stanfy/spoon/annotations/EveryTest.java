package com.stanfy.spoon.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
public @interface EveryTest {
	Action before() default Action.None;
	Action after() default Action.None;
}
