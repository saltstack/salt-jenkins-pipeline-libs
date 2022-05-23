
def call(String run_tests_stage_name,
         String nox_passthrough_opts,
         String python_version,
         String distro_version,
         String distro_arch,
         String distro_name,
         String test_suite_name_slug,
         Integer inactivity_timeout_minutes,
         Boolean run_full,
         Boolean rerun_failed_tests,
         String run_type,
         Boolean upload_test_coverage,
         Boolean upload_split_test_coverage) {

    def Integer returnStatus = 0;
    def Integer chunkReturnStatus = 0;

    def cause
    def String timeout_id
    def String timeout_message
    def String original_run_tests_stage
    def local_environ
    def List<String> test_paths
    def String chunk_name
    def List<String> ignore_paths = []

    try {
        chunk_name = "unit"
        test_paths = ["tests/unit", "tests/pytests/unit"]
        test_paths.each { path ->
            ignore_paths << "--ignore=${path}"
        }
        timeout(activity: true, time: inactivity_timeout_minutes, unit: 'MINUTES') {
            chunkReturnStatus = runTestsChunk(
                nox_passthrough_opts,
                test_paths.join(" "),
                chunk_name,
                run_type,
                python_version,
                distro_version,
                distro_arch,
                distro_name,
                test_suite_name_slug,
                upload_test_coverage,
                upload_split_test_coverage,
                rerun_failed_tests
            )
            returnStatus = returnStatus + chunkReturnStatus
            if ( chunkReturnStatus != 0 && run_full == false ) {
                error("Failed to run ${chunk_name} tests")
            }
        }
        chunk_name = "functional"
        test_paths = ["tests/pytests/functional"]
        test_paths.each { path ->
            ignore_paths << "--ignore=${path}"
        }
        timeout(activity: true, time: inactivity_timeout_minutes, unit: 'MINUTES') {
            chunkReturnStatus = runTestsChunk(
                nox_passthrough_opts,
                test_paths.join(" "),
                chunk_name,
                run_type,
                python_version,
                distro_version,
                distro_arch,
                distro_name,
                test_suite_name_slug,
                upload_test_coverage,
                upload_split_test_coverage,
                rerun_failed_tests
            )
            returnStatus = returnStatus + chunkReturnStatus
            if ( chunkReturnStatus != 0 && run_full == false ) {
                error("Failed to run ${chunk_name} tests")
            }
        }
        chunk_name = "scenarios"
        test_paths = ["tests/pytests/scenarios"]
        test_paths.each { path ->
            ignore_paths << "--ignore=${path}"
        }
        timeout(activity: true, time: inactivity_timeout_minutes, unit: 'MINUTES') {
            chunkReturnStatus = runTestsChunk(
                nox_passthrough_opts,
                test_paths.join(" "),
                chunk_name,
                run_type,
                python_version,
                distro_version,
                distro_arch,
                distro_name,
                test_suite_name_slug,
                upload_test_coverage,
                upload_split_test_coverage,
                rerun_failed_tests
            )
            returnStatus = returnStatus + chunkReturnStatus
            if ( chunkReturnStatus != 0 && run_full == false ) {
                error("Failed to run ${chunk_name} tests")
            }
        }
        chunk_name = "integration"
        test_paths = ignore_paths
        timeout(activity: true, time: inactivity_timeout_minutes, unit: 'MINUTES') {
            chunkReturnStatus = runTestsChunk(
                nox_passthrough_opts,
                test_paths.join(" "),
                chunk_name,
                run_type,
                python_version,
                distro_version,
                distro_arch,
                distro_name,
                test_suite_name_slug,
                upload_test_coverage,
                upload_split_test_coverage,
                rerun_failed_tests
            )
            returnStatus = returnStatus + chunkReturnStatus
            if ( chunkReturnStatus != 0 && run_full == false ) {
                error("Failed to run ${chunk_name} tests")
            }
        }
    } catch(org.jenkinsci.plugins.workflow.steps.FlowInterruptedException inactivity_exception) { // timeout reached
        cause = inactivity_exception.causes.get(0)
        if (cause instanceof org.jenkinsci.plugins.workflow.steps.TimeoutStepExecution.ExceededTimeout) {
            timeout_id = "inactivity-timeout"
            timeout_message = "No output was seen for ${inactivity_timeout_minutes} minutes. Aborted ${run_tests_stage_name}."
            addWarningBadge(
                id: timeout_id,
                text: timeout_message
            )
            createSummary(
                id: timeout_id,
                icon: 'warning.png',
                text: "<b>${timeout_message}</b>"
            )
        }
        throw inactivity_exception
    } catch (Exception e1) {
        error "Failed to run pipeline: ${e1}"
        throw e1
    } finally {
        return returnStatus
    }
}
