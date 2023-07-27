package jenkinsTest.utils
import groovy.json.JsonSlurperClassic

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

void setEnvIfRequired(Map test) {
    if (test.containsKey('envInput')) {
        envs = test['envInput']
        envs.each { key, val ->
            env.setProperty(key, val)
        }
    }
}

void resetEnvIfRequired(Map test) {
    if (test.containsKey('envInput')) {
        envs = test['envInput']
        envs.each { key, val ->
            env.setProperty(key, '')
        }
    }
}

@NonCPS
def jsonParse(def json) {
    new groovy.json.JsonSlurperClassic().parseText(json)
}

void loadTestCaseFile(Map config = [:]) {
    def testCasecontents = libraryResource "com/netskope/jenkinsTest/${config.name}"
    writeFile file: "${config.name}", text: testCasecontents
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

    final_summary.eachWithIndex { fxn, index ->
        println("${index + 1}.${fxn['name']}:${fxn['description']}:${fxn['status']}")
    }
    println('*******' * 10)
    println('Final Summary')
    println('Total Test Cases run:' + final_summary.size())
    println('Test Cases Passed:' + totalPass)
    println('Test Cases Failed:' + totalFail)

    if (totalFail > 0 ) {
        println('There are failed test cases. Hence, marking this job as failure')
        currentBuild.result = 'FAILURE'
    }
}


return this

