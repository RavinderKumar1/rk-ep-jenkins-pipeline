package jenkinsTest.helper
@Library('ns_pipeline_library') _

def dosomething() {
	println("do something")
}

//final_summary = []

String passOrFail(actual, expected) {
    return (actual == expected) ? 'Pass' : 'Fail'
}

void logTestCase(String name, String description, String status) {
    Map testSpecs = [:]
    testSpecs['name'] = name
    testSpecs['description'] = description
    testSpecs['status'] = status
    //println("Logging- ${name}:${description}:${status}")
    final_summary.add(testSpecs)
}

def executeNsPipelineFunction(Object... arguments) {
    argumentsList = []
    String fxnName
    def response = null

    fxnName = arguments[0]
    if (arguments.head() instanceof Map) {
        // Still to implement if required Ref: https://gist.github.com/int128/a136eb262b8f3f8decfda23542645b77
        println('Arguments ordering will be changed as last element is Map')
    }
    else {
        if (arguments.length > 1) {
            argumentsList = arguments[1..(arguments.length - 1)]
            println('   Arguments:' + arguments)
            println('   Function name is ' + fxnName)
            println('   argumentsList:' + argumentsList)
        }
    }

    try {
        if (argumentsList.size() == 0) {
            println("Executing Function: ${fxnName}()")
            response = ns_pipeline."${fxnName}"()
        }
        else {
            println("Executing Function: ${fxnName}(${argumentsList})")
            response = ns_pipeline."${fxnName}"(argumentsList)
        }
    } catch (Exception e) {
        println("Function: ${fxnName} failed with Exception: ${e}")
        currentBuild.result = 'FAILURE'
    }

    //println("Function output is: " + response)
    return response
}

void setEnv(Map envs) {
    envs.each { key, val ->
        //println("Setting env ${key}:${val}")
        env.setProperty(key, val)
    }
}

void resetEnv(Map envs) {
    envs.each { key, val ->
        //println("Resetting env ${key}")
        env.setProperty(key, '')
    }
}

void showSummary() {
    int totalFail = 0
    int totalPass = 0
    for (fxn in final_summary) {
        if (fxn['status'] == 'Pass') {
            totalPass += 1
        }
        else {
            totalFail += 1
        }
    }
    println('Final Summary')
    println('Total Test Cases run:' + final_summary.size())
    println('Test Cases Passed:' + totalPass)
    println('Test Cases Failed:' + totalFail)

    final_summary.eachWithIndex { fxn, index ->
        println("${index + 1}.${fxn['name']}:${fxn['description']}:${fxn['status']}")
    }
    if (totalFail > 0 ) {
        println('There are failed test cases. Hence, marking this job as failure')
        currentBuild.result = 'FAILURE'
    }
}


return this
