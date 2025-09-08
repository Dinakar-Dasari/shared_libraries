def call(Map configMap){
        pipeline {
            agent {
                label 'agent-1'
            }
            tools {
                nodejs 'maven'
            }

            environment {
                appVersion=''
            // SONAR_SCANNER_HOME = tool 'sonar-7.2';
                PROJECT = configMap.get('project')
                COMPONENT = configMap.get("component")
                REGION = 'us-east-1'
                ACC_ID = '127218179061'
            }

            parameters{
                booleanParam(name: 'deploy', defaultValue: false, description: 'is it good to deploy?')
            }

            options {
                timeout(time:30, unit:'MINUTES')  // pipeline will terminate if exceeds 30 minutes
            }

            stages{

                stage('clean up') {
                    steps {
                        deleteDir()
                    }
                }

                stage('git checkout') {
                    steps {
                        git branch: 'main', url: 'https://github.com/Dinakar-Dasari/jenkins_latest.git'
                    }
                }

        /// git branch: 'main', url: 'https://github.com/Dinakar-Dasari/jenkins_latest.git'
        /// Can skip git checkout as it will clone already when we use the URL in the UI

                stage('get app version') {
                    steps{
                        script{
                            appVersion = readMavenPom().getVersion()
                            echo "Package version: ${appVersion}"
                        }    
                    }
                }  
                stage('install dependencies') {
                    steps{
                        sh 'mvn clean package'
                    }
                }

                stage('test') {
                    steps{
                        echo "run test cases"
                    }
                }

            /*  stage("dependency scan") {
                    steps{
                        dependencyCheck additionalArguments: """
                        --scan ./ 
                        --format 'ALL' 
                        --prettyPrint 
                        """,
                        odcInstallation: 'owsap-dependency' //tool name which we configured in tool section
                        dependencyCheckPublisher pattern: '**\/dependency-check-report.xml', stopBuild: false
                        // by default the build will not fail if we don't give failedTotalMedium: 1, stopBuild: true
                    }
                } 

                stage('SAST-sonar') {
                    steps{
                        timeout(time: 60, unit: 'SECONDS') {
                                withSonarQubeEnv('sonar-server') {
                                sh """
                                    $SONAR_SCANNER_HOME/bin/sonar-scanner \
                                        -Dsonar.projectKey=$COMPONENT \
                                        -Dsonar.sources=server.js \
                                """
                                }

                            // waitForQualityGate abortPipeline: true  
                            // pipeline will fail because there is no code coverage. So, to continue we are disabling it
                        }      
                    }
                } */

            /* stage('Image build') {
                    steps{
                        withAWS(credentials: 'aws-creds', region: 'us-east-1') {
                            sh """
                                aws ecr get-login-password --region ${REGION} | docker login --username AWS --password-stdin ${ACC_ID}.dkr.ecr.us-east-1.amazonaws.com
                                docker build -t ${ACC_ID}.dkr.ecr.us-east-1.amazonaws.com/${PROJECT}/${COMPONENT}:${appVersion} .
                                docker push ${ACC_ID}.dkr.ecr.us-east-1.amazonaws.com/${PROJECT}/${COMPONENT}:${appVersion}
                            """
                        }
                    }
                } */

                // ${ACC_ID}.dkr.ecr.us-east-1.amazonaws.com --> AWS ECR registry name
                // we don't give(docker.io/dd070/nginx:1.0.0) for docker bcz by default it will check in docker registery

                stage('image build'){
                    steps {
                        sh " docker build -t dd070/$COMPONENT:$appVersion . "
                    }
                }    

                stage('Trivy scan'){
                    steps {
                        sh  """ 
                            trivy image dd070/$COMPONENT:$appVersion \
                                --severity LOW,MEDIUM,HIGH \
                                --exit-code 0 \
                                --quiet \
                                --format json -o trivy-image-MEDIUM-results.json  

                            trivy image dd070/$COMPONENT:$appVersion \
                                --severity CRITICAL \
                                --exit-code 1 \
                                --quiet \
                                --format json -o trivy-image-CRITICAL-results.json
                        """
                    }

                    // To convert to html / xml format
                    post {
                        always {
                            sh '''
                                trivy convert \
                                    --format template --template "@/usr/local/share/trivy/templates/html.tpl" \
                                    --output trivy-image-MEDIUM-results.html trivy-image-MEDIUM-results.json 

                                trivy convert \
                                    --format template --template "@/usr/local/share/trivy/templates/html.tpl" \
                                    --output trivy-image-CRITICAL-results.html trivy-image-CRITICAL-results.json

                                trivy convert \
                                    --format template --template "@/usr/local/share/trivy/templates/junit.tpl" \
                                    --output trivy-image-MEDIUM-results.xml  trivy-image-MEDIUM-results.json 

                                trivy convert \
                                    --format template --template "@/usr/local/share/trivy/templates/junit.tpl" \
                                    --output trivy-image-CRITICAL-results.xml trivy-image-CRITICAL-results.json          
                            '''
                        }
                    }    

                }

                stage('Push docker image'){
                    steps {
                        withDockerRegistry(credentialsId: 'docker-hub-creds', url: "") {
                            sh  "docker push dd070/$COMPONENT:$appVersion"
                        } 
                    }
                }

                stage("Trigger Deploy"){
                    when{
                        expression{params.deploy }
                    }
                    steps{
                        script{
                            build job: "../${COMPONENT}_cd",
                            parameters: [
                                string(name: 'app_Version', value: "${appVersion}"),
                                string(name: 'deploy_to', value: 'DEV')
                            ],
                            propagate: false, //even catalogue_cd fails, it doesn't effect this build
                            wait: false  // This build won't wait and continue for later stages      
                        }

                    }
                }
                

            }

            post { 
                success { 
                    echo 'Hello Success'
                }
                failure { 
                    echo 'Hello Failure'
                }
            }
        }
    }