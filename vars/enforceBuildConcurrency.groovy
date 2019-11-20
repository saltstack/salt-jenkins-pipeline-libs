def call(Map options) {

    def env = options.get('env')
    def Integer concurrent_builds = options.get('concurrent_builds', 1)
    def Boolean enforce_concurrency_on_branch_builds = options.get('enforce_concurrency_on_branch_builds', false)
    def buildNumber = env.BUILD_NUMBER as int

    if ( env.CHANGE_ID || ( ! env.CHANGE_ID && enforce_concurrency_on_branch_builds ) ) {
        // Only set milestones on PR builds or if explicitly asked to on branch builds
        if ( concurrent_builds > 0) {
            // If concurrent_builds <= 0 it means it was explicitly disabled
            if (buildNumber > concurrent_builds) {
                // This will cancel the previous build which also defined a matching milestone
                milestone(buildNumber - concurrent_builds)
            }
            // Define a milestone for this build so that, if another build starts, this one will be aborted
            milestone(buildNumber)
        }
    }
}
