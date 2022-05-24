
def call(String stage_name,
         String python_version,
         String distro_version,
         String distro_arch,
         String distro_name,
         String test_suite_name_slug) {

    def Integer returnStatus = 0;

    stage(stage_name) {
        try {
            withEnv(["DONT_DOWNLOAD_ARTEFACTS=1", "ONLY_INSTALL_REQUIREMENTS=1"]){
                sh label: 'Install test requirements', script: 'bundle exec kitchen verify $TEST_SUITE-$TEST_PLATFORM || exit 0'
            }
            sh label: 'Rename logs', script: """
            if [ -s ".kitchen/logs/${python_version}-${distro_name}-${distro_version}-${distro_arch}.log" ]; then
                mv ".kitchen/logs/${python_version}-${distro_name}-${distro_version}-${distro_arch}.log" ".kitchen/logs/${python_version}-${distro_name}-${distro_version}-${distro_arch}-${test_suite_name_slug}-install-requirements.log"
            fi
            if [ -s ".kitchen/logs/kitchen.log" ]; then
                mv ".kitchen/logs/kitchen.log" ".kitchen/logs/kitchen-${test_suite_name_slug}-install-requirements.log"
            fi
            """
            returnStatus = 0
        } catch (error) {
            returnStatus = 1
            error "Failed to install the test requirements"
            throw error
        } finally {
            archiveArtifacts(
                artifacts: ".kitchen/logs/*-install-requirements.log",
                allowEmptyArchive: true
            )
            return returnStatus
        }
    }
}
