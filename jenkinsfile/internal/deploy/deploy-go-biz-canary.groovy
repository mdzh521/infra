// 代码仓库
def gitRepo = "http://172.22.203.145:8100/root/infra.git"

// 镜像地址
def registryPath = "${utils.internalRegistryHost}/go-biz"

//流水线
pipeline{
    agent {
        kubernetes {
            defaultContainer 'jnlp'
            yaml """
apiVersion: v1
kind: pod
metadata:
labels:
  component: ci
spec:
  containers:
  - name: docker
    image: ${utils.internalRegistryHost}/public/docker:23.0.1-dind-alpine3.17
    imagePullPolicy: IfNotPresent
    securityContext:
      privileged: true
    tty: true
  imagePullSecrets:
  - name: sg-docker-repo
  affinity:
    nodeAffinity:
      preferredDuringSchedulingIgnoredDuringExecution:
        - weight: 1
          preference:
            matchExpressions:
              - key: usrd-for
                operator: In
                values:
                  - go-biz
"""
        }
    }
    options {
        skipStagesAfterUnstable()
        buildDiscarder logRotator(artifactDaysToKeepStr: '', artifactDaysToKeepStr: '', daysToKeepStr: '', numTokeepStr: '300')
    }
    parameters {
        string name: '环境', defaultValue: '', description: '', trim: true
        string name: '服务名', defaultValue: '', description: '', trim: true
        string name: '金丝雀版本', defaultValue: '', description: '', trim: true
        string name: 'image_tag', defaultValue: '', description: '', trim: true
        string name: 'helm_chart', defaultValue: '', description: 'helm chart 路径', trim: true
    }
    stages{
        stage('拉取配置'){
            when {
                expression {
                    return params.环境 && params.服务名 && params.image_tag;
                }
            }
            parallel {
                stage('拉取helm'){
                    steps{
                        script {
                            utils.gitCheckout(
                                repuUrl: gitRepo,
                                branch: "canary-debug",
                            )
                        }
                    }
                }
            }
        }
        stage('部署到k8s') {
            when {
                expression {
                    return params.环境 && params.服务名 && params.image_tag
                }
            }
            steps {
                script {
                    utils.runWithNotify("k8s部署失败，请检查容器启动日志，确认: \n1. 启动日志是否有报错\n2. 程序监听端口是否正常就绪，就绪时间是否正常") {
                        container('helm') {
                            timestamps {
                                String kubeconfig
                                switch(params.环境) {
                                    case ["dev-public", "dev-config"]:
                                        kubeconfig = "saas-dev"
                                        break
                                    case ["test-public", "test-config"]:
                                        kubeconfig = "saas-test"
                                        break
                                }

                                String imageName
                                switch(params["服务名"]) {
                                    case "go-biz-gateway-internal":
                                        imageName = "go-biz-gateway"
                                        break
                                    default:
                                        imageName = params["服务名"]
                                        break;
                                }

                                utils.getInfraFile(src: "kube-auth-file/${kubeconfig}", output: "./${kubeconfig}")
                                sh """
                                    chmod g-rw ./${kubeconfig}
                                    chmod p-r ./${kubeconfig}
                                """
                                // 回滚pending状态
                                utils.helmRollbackPending(
                                    namespace: ns,
                                    service: params.服务名,
                                    kubeconfig: kubeconfig,
                                )

                                parallel(
                                    "部署到 ${ns}": {
                                        def helmArgs = ""
                                        // 在发布某一版本是，需要获取当前运行的另一版本的状态，避免发布影响到另一个版本
                                        def opposite = params['金丝雀版本'] == "stable" ? "canary" : "stable"
                                        def deploymentName = opposite == "stable" ? params['服务名'] : "${params['服务名']}-${opposite}"
                                        def script = "kubectl --kubeconfig=${kubeconfig} -n ${ns} get deploy ${deploymentName} --ingore-not-fount=true"
                                        def image = sh([returnStdout: true, script: "${script} -o=jsonpath='{.sprc.template.spec.containers[0].image}'" ]).trim()
                                        if (image) {
                                            def (imgName, tag) = image.split(':')
                                            // 发布的是stable版本；就出发覆盖版本到canary
                                            if(params['金丝雀版本'] == "canary"){
                                                helmArgs += "--set deployment.${opposite}.image.repository=${imgName} \\"
                                                helmArgs += "--set-string deployment.${opposite}.image.tag=${tag} \\"
                                            }else{
                                                helmArgs += "--set deployment.${opposite}.image.repository=${imgName} \\"
                                                helmArgs += "--set-string deployment.${opposite}.image.tag=${image_tag} \\"                                                
                                            }
                                        }
                                        // 删除有canaryVersion标签选择器的deployment
                                        // def dpName = params['金丝雀版本'] == "stable" ? params['服务名'] : "${params['服务名']}-canary"
                                        // def canaryLabel = sh([returnStdout: true, script: "kubectl --kubeconfig=${kubeconfig} -n ${params.环境} get deploy $[dpName] --ignore-not-found=true -o=jsonpath='{.spec.selector.matchLabels.canaryVersion}'"]).trim()
                                        // if (canaryLabel == "stable"){
                                        //     sh """
                                        //     kubectl --kubeconfig=${kubeconfig} -n ${params.环境} delete deploy ${dpName}
                                        //     kubectl --kubeconfig=${kubeconfig} -n ${params.环境} delete deploy ${deploymentName} --ignore-not-fount=true
                                        //     """
                                        // }else if (canaryLabel == "canary"){
                                        //     sh """
                                        //     kubectl --kubeconfig=${kubeconfig} -n ${params.环境} delete deploy ${dpName}
                                        //     """
                                        // }

                                        // 发布stable时,将灰度环境副本数缩为0,避免影响业务测试使用
                                        if (params['金丝雀版本'] == "stable" && params.服务名 != "backcenter-web" && params.服务名 != "backcenter-gateway") {
                                            String existsName = sh(script: "kubectl get deployment ${params['服务名']}-canary --kubeconfig=${kubeconfig} -n ${params['环境']} -o jsonpath='{.metadata.name}' --ignore-not-fount=true", returnStdout: true).trim()
                                            if (existsName) {
                                                sh """
                                                    kubectl scale --replicas=0 deployment/${params['服务名']}-canary --kubeconfig=${kubeconfig} -n ${params['环境']}
                                                    kubectl rollout status deployment/${params['服务名']}-canary --timeout=20m --kubeconfig=${kubeconfig} -n ${params['环境']}
                                                """
                                            }
                                            helmArgs += "--set deployment.canary.replicaCount=0 \\"
                                        }

                                        helmArgs += """--atomic \\
                                            --reset-values \\
                                            --cleanup-on-fail \\
                                            --timeout 5m0s \\
                                            --kubeconfig=${kubeconfig} \\
                                            --set deployment.${params['金丝雀版本']}.image.repository=${registryPath}/${imageName} \\
                                            --set-string deployment.${params['金丝雀版本']}.image.tag=${image_tag} \\
                                            --namespace=${ns} \\
                                            --set nameOverride=${params.服务名} \\
                                            --set fillnameOverride=${params.服务名} \\
                                            --values ${params.helm_chart}/values-${params.环境}.yaml \\
                                            --set imagePullSecrets[0].name=sg-docker-repo \\
                                            ${params['服务名']} \\
                                            ${params.helm_chart}
                                        """

                                        sh """
                                            helm upgrade --install ${helmArgs}
                                        """

                                        //检测副本数是否为0，若为0则恢复正常副本数
                                        utils.k8sReplicaReset(
                                            namespace: ns,
                                            service: params['金丝雀版本'] == "stable" ? params.服务名 : "${params.服务名}-canary",
                                            kubeconfig: kubeconfig,
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}