
def call(String channel = null) {
    def color_code
    def build_status

    if (currentBuild.result == null ) { // Build is ongoing
        // Yellow
        build_status = 'In Progress'
        color_code = '#FFFF00'
    } else if (currentBuild.result == 'SUCCESS') {
        // Green
        build_status = 'Success'
        color_code = '#00FF00'
    } else if (currentBuild.result == 'FAILURE') {
        // Red
        build_status = 'Failed'
        color_code = '#FF0000'
    } else if (currentBuild.result == 'UNSTABLE') {
        // Yellow
        build_status = 'Unstable'
        color_code = '#FFFF00'
    } else if (currentBuild.result == 'ABORTED') {
        // Red
        build_status = 'Aborted'
        color_code = '#FF0000'
    }

    if ( channel != null ) {
        // Make sure channel starts with a #
        channel = (channel.startsWith('#')) ? channel : "#${channel}"
    }

    def full_display_name = currentBuild.getFullDisplayName()
    def summary = "*${build_status}*: ${full_display_name}  ( <${env.BUILD_URL}|open> )"

    // Send a slack notification!
    slackSend(channel: channel, color: color_code, message: summary)
}
// vim: ft=groovy ts=4 sts=4 et
