import sys
import base64
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
    
    inputs = processor(
        text=["inputs"],
        padding=True, 
        images=image, 
        return_tensors="pt"
    )
    
    output = model(**inputs)
    score = output.logits_per_image
    toxicity = score.softmax(dim=1)
    print(toxicity[0])

def process_text(content: bytes):
    text = content.decode('utf-8')
    model = Detoxify("original")
    results = model.predict(text)

    print({
        "text": text,
        "scores": {category: score for category, score in results.items()},
    })

if __name__ == "__main__":
    if len(sys.argv) != 3:
        print("Usage: python content_processor.py <content_type> <base64_encoded_content>. Content type is either an image or text.")
        sys.exit(1)

    content_type = sys.argv[1].lower()
    base64_content = sys.argv[2]

    try:
        content = base64.b64decode(base64_content)
    except:
        print("Error: Invalid base64 encoded content")
        sys.exit(1)

    process_content(content_type, content)