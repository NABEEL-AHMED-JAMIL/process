echo "===============================================================";
echo "Process Application";
echo "User: Nabeel Ahmed"
echo "Email: nabeel.amd93@gmail.com"
echo "===============================================================";

# Step 1: Running Maven clean to remove target directory...
echo "Step 1: Running Maven clean to remove target directory..."
mvn clean

# Step 2: Running Maven install to compile and package the project...
echo "Step 2: Running Maven install to compile and package the project..."
mvn install

# Step 3: Build Docker Image...
echo "Step 3: Build Docker Image..."
docker build -t ballistic/process:latest .

# Step 4: Docker Hub Login
echo "Step 4: Docker Hub Login"
docker login -u ballistic -p dckr_pat_tbeLFqXGfCqWXDUQE_xeqTFl0Lw

# Step 5: Docker Image Upload
echo "Step 5: Docker Image Upload"
docker push ballistic/process-v2:latest

# Step 6: Run Kubernat
