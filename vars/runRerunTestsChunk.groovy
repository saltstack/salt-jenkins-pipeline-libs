import org.jenkinsci.plugins.pipeline.modeldefinition.Utils


def call(String nox_passthrough_opts,
         String test_paths,
         String chunk_name,
         String run_type,
         String python_version,
         String distro_version,
         String distro_arch,
         String distro_name,
         String test_suite_name_slug,
         Boolean upload_test_coverage,
         Boolean upload_split_test_coverage,
         Boolean rerun_failed_tests) {

    def Integer returnStatus = 1;
    def rerun_in_progress = false
    def previous_build_status = currentBuild.currentResult

    try {
        try {
            returnStatus = runTestsChunk(
                nox_passthrough_opts,
                test_paths,
                chunk_name,
                run_type,
                python_version,
                distro_version,
                distro_arch,
                distro_name,
                test_suite_name_slug,
                upload_test_coverage,
                upload_split_test_coverage,
                rerun_in_progress
            )
        } catch(Exception run1) {
            returnStatus = 1
            if ( rerun_failed_tests == true ) {
                echo "runRerunTestsChunk:run1: Failed to run ${chunk_name.capitalize()} Tests ${run_type}. Re-trying failed tests: ${run1}"
            } else {
                error "runRerunTestsChunk:run1: Failed to run ${chunk_name.capitalize()} Tests ${run_type}: ${run1}"
                throw run1
            }
        } finally {
            if ( returnStatus != 0 && rerun_failed_tests == true ) {
                // Reset the build status to what it was prior to the first tests run
                currentBuild.result = previous_build_status
                rerun_in_progress = true
                try {
                    returnStatus = runTestsChunk(
                        nox_passthrough_opts,
                        test_paths,
                        chunk_name,
                        run_type,
                        python_version,
                        distro_version,
                        distro_arch,
                        distro_name,
                        test_suite_name_slug,
                        upload_test_coverage,
                        upload_split_test_coverage,
                        rerun_in_progress
                    )
                } catch(Exception run2) {
                    returnStatus = 1
                    error "runRerunTestsChunk:run2: Failed to re-run ${chunk_name.capitalize()} Tests ${run_type}: ${run2}"
                    throw run2
                }
            } else {
                def skipped_stage_name = "${chunk_name.capitalize()} Tests ${run_type} (Re-run Failed)"
                stage("Run ${skipped_stage_name}") {
                    Utils.markStageSkippedForConditional("Run ${skipped_stage_name}")
                }
            }
        }
    } catch(Exception run3) {
        if ( rerun_failed_tests == true ) {
            echo "runRerunTestsChunk:run3: Failed to run ${chunk_name.capitalize()} Tests ${run_type}. Re-trying failed tests: ${run3}"
        } else {
            error "runRerunTestsChunk:run3: Failed to run ${chunk_name.capitalize()} Tests ${run_type}: ${run2}"
        }
        throw run3
    } finally {
        try {
            if ( returnStatus != 0 ) {
                currentBuild.result = 'FAILURE'
                if ( rerun_failed_tests == true ) {
                    error "runRerunTestsChunk:finally: Failed to run(and re-run failed) ${chunk_name.capitalize()} Tests ${run_type}."
                } else {
                    error "runRerunTestsChunk:finally: Failed to run ${chunk_name.capitalize()} Tests ${run_type}."
                }
            }
        } finally {
            return returnStatus
        }
    }
}
