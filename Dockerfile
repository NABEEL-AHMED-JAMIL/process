# JDK Run time
FROM openjdk:11

# name of authors
LABEL maintainer="Nabeel Ahmed <nabeel.amd93@gmail.com>"
LABEL version="1.0"
LABEL description="This is a docker image for a rest api for scheduler system."
LABEL description="Github detil [https://github.com/NABEEL-AHMED-JAMIL/process]"

# variable that can be use to store the info of project detail and its can be use in docker file
ARG JAR_FILE=target/*.jar

# copy all the detail from varaible to use the variable in the coker ${JAR_FILE}
COPY ${JAR_FILE} process.jar

# EXPOSE the port
EXPOSE 9098

# help to run the file
ENTRYPOINT ["java","-jar","/process.jar"]