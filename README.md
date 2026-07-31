# CalorieTracker-BackEnd


Setting up the classifier

Use source .venv because linux.
python3 -m venv .venv
source .venv/bin/activate
pip install --upgrade pip
pip install torch torchvision torchaudio --index-url https://download.pytorch.org/whl/cpu
pip install transformers pillow fastapi uvicorn python-multipart


https://huggingface.co/datasets/ethz/food101

I didn't upload the actual tokens for fatsecret api, if needed for testing shoot me a msg.


Setting up the server from ground up

curl "https://start.spring.io/starter.zip?type=maven-
project&language=java&groupId=com.example&artifactId=fatsecret-spring-
test&name=fatsecret-spring-
test&packageName=com.example.fatsecrettest&javaVersion=17&dependencies=web" \
-o project.zip
Unzip

vim src/main/resources/application.yml
fatsecret:
client-id: ${}
client-secret: ${}
Snippets are on fatsecret docs just bundle together
export FATSECRET_CLIENT_ID=""
export FATSECRET_CLIENT_SECRET=""
and run ./mvnw spring-boot:run
