
def call(String credentials_id, Closure body=null) {

    withCredentials([
        usernamePassword(
            credentialsId: credentials_id,
            passwordVariable: 'DOCKER_PASSWORD',
            usernameVariable: 'DOCKER_USERNAME')
    ]) {
        if (body) { body() }
    }
}
// vim: ft=groovy ts=4 sts=4 et
