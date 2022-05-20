
def call() {
    try {
        sh '''
        bundle exec kitchen exec $TEST_SUITE-$TEST_PLATFORM -c "sudo python -c \\"import shutil; shutil.rmtree('artifacts', ignore_errors=True);\\""
        '''
    } catch (Exception error) {
        echo "Failed to delete remote artifacts directory: ${error}"
    }
}
