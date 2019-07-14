def call(Integer how_many_back = 10) {
    def current_build_number = env.BUILD_NUMBER as int
    def oldest_build_number = current_build_number - how_many_back
    if (oldest_build_number < 1) {
        oldest_build_number = 1
    }
    def build_range = oldest_build_number..current_build_number
    for(def build_nr: build_range) {
        milestone(build_nr)
    }
}

// vim: ft=groovy ts=4 sts=4 et
