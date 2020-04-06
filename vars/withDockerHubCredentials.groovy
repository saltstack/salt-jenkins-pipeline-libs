
def call(String credentials_id, Closure body=null) {

    try {
        withCredentials([
            usernamePassword(
                credentialsId: credentials_id,
                passwordVariable: 'DOCKER_PASSWORD',
                usernameVariable: 'DOCKER_USERNAME')
        ]) {
            sh """
            echo \${DOCKER_PASSWORD} | docker login --username \${DOCKER_USERNAME} --password-stdin https://index.docker.io/v1/
            """
        }
        if (body) { body() }
    } finally {
        sh """
        docker logout https://index.docker.io/v1/
        """
    }
}
// vim: ft=groovy ts=4 sts=4 et
