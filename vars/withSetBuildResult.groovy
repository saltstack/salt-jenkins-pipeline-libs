
def call(String slack_channel = null, Closure body=null) {
    try {
        if (body) { body() }
        currentBuild.result = 'SUCCESS'
        echo "Setting currentBuild.result to ${currentBuild.result}"
    } catch(InterruptedException ie) {
        currentBuild.result = 'ABORTED'
        echo "Setting currentBuild.result to ${currentBuild.result}"
        throw ie
    } catch(e) {
        currentBuild.result = 'FAILURE'
        echo "Setting currentBuild.result to ${currentBuild.result}"
        throw e
    } finally {
        sendSlackNotification(slack_channel)
    }
}
// vim: ft=groovy ts=4 sts=4 et
