import base64
import sys
from io import BytesIO

from detoxify import Detoxify
from PIL import Image
from transformers import CLIPModel, CLIPProcessor


def process_content(content_type: str, content: bytes):
    if content_type == "image":
        process_image(content)
    elif content_type == "text":
        process_text(content)
    else:
        print("Invalid content type. Please specify 'image' or 'text'.")


def process_image(content: bytes):
    image = Image.open(BytesIO(content))
    model = CLIPModel.from_pretrained("openai/clip-vit-base-patch32")
    processor = CLIPProcessor.from_pretrained("openai/clip-vit-base-patch32")

    prompt_list = ["colorful", "bright", "yellow", "square"]
    inputs = processor(
        text=prompt_list,
        padding=True,
        images=image,
        return_tensors="pt",
    )

    output = model(**inputs)
    score = output.logits_per_image
    probability = score.softmax(dim=1)[0]
    print(probability)
    print(
        {label: round(float(prob), 6) for label, prob in zip(prompt_list, probability)}
    )


def process_text(content: bytes):
    text = content.decode("utf-8")
    model = Detoxify("original")
    results = model.predict(text)

    print(
        {
            "text": text,
            "scores": {category: float(score) for category, score in results.items()},
        }
    )


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print(
            "Usage: python content_processor.py <content_type> <byte_array>. Content type is either an image or text."
        )
        sys.exit(1)

    content_type = sys.argv[1].lower()
    base64_content = sys.argv[2]

    try:
        content = base64.b64decode(base64_content)
    except:
        print("Error: Invalid base64 encoded content")
        sys.exit(1)

    process_content(content_type, content)
