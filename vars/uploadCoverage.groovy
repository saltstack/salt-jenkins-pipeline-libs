
def uploadCodeCoverage(String coverage_file_path,
                       String coverage_report_name,
                       String[] coverage_report_flags,
                       Integer upload_retries = 3,
                       String credentials_id = 'codecov-upload-token-salt',
                       String credentials_variable_name = 'CODECOV_TOKEN') {

    withEnv([
        "REPORT_FILE=${coverage_file_path}",
        "REPORT_NAME=${coverage_report_name}",
        "REPORT_FLAGS=${coverage_report_flags.join(',')}"
    ]) {
        try {
            retry(upload_retries) {
                script {
                    withCredentials([[$class: 'StringBinding', credentials_id: credentialsId, variable: credentials_variable_name]]) {
                        sh '''
                            curl -L https://codecov.io/bash | /bin/sh -s -- -R $(pwd) -n "${REPORT_NAME}" -f ${REPORT_FILE} -F "${REPORT_FLAGS}"
                        '''
                    }
                }
            }
        } catch (Exception e) {
            echo "Failed to upload to codecov after ${upload_retries}: ${e}"
        }
    }
}
// vim: ft=groovy ts=4 sts=4 et
