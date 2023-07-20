package jenkins.groovy

def obj
class PipelineTestHelper implements Serializable {
    def helperFunction() {
        println('Calling helper function from PipelineTestHelper')
    }
    PipelineTestHelper(obj) {
        this.obj = obj
        obj.println("Calling helper class constructor")
        
    }
    
}
