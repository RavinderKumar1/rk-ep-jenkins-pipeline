package jenkinsTest.helper

def dosomething() {
	println("do something")
}


String passOrFail(actual, expected) {
    return (actual == expected) ? 'Pass' : 'Fail'
}

void logTestCase(String name, String description, String status, final_summary) {
    Map testSpecs = [:]
    testSpecs['name'] = name
    testSpecs['description'] = description
    testSpecs['status'] = status
    //println("Logging- ${name}:${description}:${status}")
    final_summary.add(testSpecs)
    return final_summary
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

void showSummary(final_summary) {
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
