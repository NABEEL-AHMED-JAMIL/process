echo "===============================================================";
echo "Process Application";
echo "User: Nabeel Ahmed"
echo "Email: nabeel.amd93@gmail.com"
echo "===============================================================";

# project local build
echo "Project Clean Start"
# Step 2: Running Maven clean to remove target directory
echo "Step 1: Running Maven clean to remove target directory..."
mvn clean
echo "===============================================================";
# mvn install
echo "Step 2: Running Maven install to compile and package the project..."
mvn install

# running the docker image detail
current_datetime=$(date +%Y-%m-%d_%H-%M-%S)
container_name="process-${current_datetime}"
echo "======================$container_name==========================";
echo "Step 3: Build Docker Image..."
docker build -t process .
echo "===============================================================";
echo "Step 4: Run Docker container..."
docker run -d --name "$container_name" process
echo "Build And Run Process Complete."