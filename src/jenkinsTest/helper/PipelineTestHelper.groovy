package jenkinsTest.helper

def obj
class PipelineTestHelper {
    void helperFunction() {
        println('Calling helper function from PipelineTestHelper')
    }
    PipelineTestHelper(obj) {
	this.obj = obj
	obj.println("PipelineTest helper constructor called")
    }
}
