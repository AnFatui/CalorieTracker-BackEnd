from fastapi import FastAPI, File, HTTPException, UploadFile
from PIL import Image, UnidentifiedImageError
from transformers import pipeline
import io
import time

# Most of this is taken from the hugging face, timings were added for debugging and pipline for communicating
MODEL_ID = "nateraw/food"

app = FastAPI()

# Load the model once when the Python service starts.
classifier = pipeline(
    task="image-classification",
    model=MODEL_ID
)


@app.post("/classify")
async def classify_food(image: UploadFile = File(...)):
    if image.content_type is None or not image.content_type.startswith("image/"):
        raise HTTPException(
            status_code=400,
            detail="The uploaded file must be an image."
        )

    image_bytes = await image.read()

    if not image_bytes:
        raise HTTPException(
            status_code=400,
            detail="The uploaded image is empty."
        )

    try:
        with Image.open(io.BytesIO(image_bytes)) as source_image:
            width, height = source_image.size
            image_format = source_image.format or "unknown"

            # Create a separate RGB image before the source image is closed.
            rgb_image = source_image.convert("RGB")

    except UnidentifiedImageError:
        raise HTTPException(
            status_code=400,
            detail="The uploaded file is not a valid image."
        )

    inference_start = time.perf_counter()

    predictions = classifier(
        rgb_image,
        top_k=5
    )

    inference_ms = (
        time.perf_counter() - inference_start
    ) * 1000

    normalized_predictions = []

    for prediction in predictions:
        normalized_predictions.append(
            {
                "label": prediction["label"].replace("_", " "),
                "confidence": float(prediction["score"])
            }
        )

    best_prediction = normalized_predictions[0]

    return {
        "label": best_prediction["label"],
        "confidence": best_prediction["confidence"],
        "modelInferenceMs": inference_ms,
        "width": width,
        "height": height,
        "imageFormat": image_format,
        "topPredictions": normalized_predictions
    }
