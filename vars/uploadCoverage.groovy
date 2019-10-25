
def uploadCodeCoverage(Map options) {

    def String coverage_file_path = options.get('coverage_file_path')
    def String coverage_report_name = options.get('coverage_report_name')
    def String[] coverage_report_flags = options.get('coverage_report_flags')
    def Integer upload_retries = optional.get('upload_retries', 3)
    def String credentials_id = optional.get('credentials_id', 'codecov-upload-token-salt')
    def String credentials_variable_name = optional.get('credentials_variable_name', 'CODECOV_TOKEN')

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
