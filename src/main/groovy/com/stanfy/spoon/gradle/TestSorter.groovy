package com.stanfy.spoon.gradle

import com.stanfy.spoon.annotations.Action
import com.stanfy.spoon.annotations.AfterTest
import com.stanfy.spoon.annotations.BeforeTest
import com.stanfy.spoon.annotations.EveryTest
import javassist.CtClass
import org.junit.Ignore
import org.junit.Test

class TestSorter {

    private def clearAlways = []
    private def clearBefore = []
    private def clearAfter = []
    private def clearBeforeStopAfter = []
    private def clearAfterStopBefore = []
    private def stopAlways = []
    private def stopBefore = []
    private def stopAfter = []
    private def doNothing = []

    private def criteriaMap = [:]

    private def sorted = []

    TestSorter(Collection<CtClass> classes, boolean sort) {
        criteriaMap[[Action.ClearData,  Action.ClearData]]  =   clearAlways
        criteriaMap[[Action.ClearData,  Action.ForceStop]]  =   clearBeforeStopAfter
        criteriaMap[[Action.ClearData,  Action.None]]       =   clearBefore

        criteriaMap[[Action.ForceStop,  Action.ClearData]]  =   clearAfterStopBefore
        criteriaMap[[Action.ForceStop,  Action.ForceStop]]  =   stopAlways
        criteriaMap[[Action.ForceStop,  Action.None]]       =   stopBefore

        criteriaMap[[Action.None,       Action.ClearData]]  =   clearAfter
        criteriaMap[[Action.None,       Action.ForceStop]]  =   stopAfter
        criteriaMap[[Action.None,       Action.None]]       =   doNothing

        if (sort) {
            init(classes)
        } else {
            sorted.addAll(classes)
        }
    }

    private def init(Collection<CtClass> classes) {
        classes.each {
            EveryTest annotation = it.getAnnotation(EveryTest) as EveryTest
            Action before = annotation ? annotation.before() : Action.None
            Action after = annotation ? annotation.after() : Action.None
            def methods = it.methods.findAll { !it.hasAnnotation(Ignore) && it.hasAnnotation(Test) }
            if (methods) {
                def beforeTest = methods.first().getAnnotation(BeforeTest) as BeforeTest
                Action methodFirst = beforeTest?.value()
                if (methodFirst && methodFirst.ordinal() > before.ordinal()) {
                    before = methodFirst
                }

                def afterTest = methods.first().getAnnotation(AfterTest) as AfterTest
                Action methodLast = afterTest?.value()
                if (methodLast && methodLast.ordinal() > after.ordinal()) {
                    after = methodLast
                }
            }

            def list = criteriaMap[[before, after]]
            list << it
        }

        addOne(clearAfter)
        addAll(clearAlways)
        addOne(clearBefore)
        addOne(stopAfter)
        addAll(stopAlways)
        addOne(stopBefore)
        addAll(doNothing)
        addTriples(clearAfter, clearBeforeStopAfter, stopBefore)
        addTriples(stopAfter, clearAfterStopBefore, clearBefore)
        addPairs(clearAfter, clearBefore)
        addPairs(stopAfter, stopBefore)
        addPairs(clearAfter, stopBefore)
        addPairs(stopAfter, clearBefore)
        addPairs(clearAfterStopBefore, clearBeforeStopAfter)
        addPairs(clearAfter, clearBeforeStopAfter)
        addPairs(stopAfter, clearAfterStopBefore)
        addPairs(clearBeforeStopAfter, stopBefore)
        addPairs(clearAfterStopBefore, clearBefore)
        addAll(clearBefore)
        addAll(clearAfter)
        addAll(clearAfterStopBefore)
        addAll(clearBeforeStopAfter)
        addAll(stopBefore)
        addAll(stopAfter)

    }

    private def addOne(List<CtClass> listFrom) {
        if (listFrom) {
            sorted << listFrom.remove(0)
        }
    }

    private def addAll(List<CtClass> listFrom) {
        sorted.addAll(listFrom)
        listFrom.clear()
    }

    private def addPairs(List<CtClass> listOne, List<CtClass> listTwo) {
        while (listOne && listTwo) {
            sorted << listOne.remove(0) << listTwo.remove(0)
        }
    }

    private def addTriples(List<CtClass> listOne, List<CtClass> listTwo, List<CtClass> listThree) {
        while (listOne && listTwo && listThree) {
            sorted << listOne.remove(0) << listTwo.remove(0) << listThree.remove(0)
        }
    }

    def getTests() {
        return sorted.collectMany {
            it.methods.findAll { it.hasAnnotation(Test) }
        }
    }

}
