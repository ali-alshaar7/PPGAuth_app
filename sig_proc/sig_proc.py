import pandas as pd
import random
import pickle

import openpyxl
import numpy as np
import matplotlib.pyplot as plt
from itertools import combinations, product


import torch
import torch.nn as nn
import torch.optim as optim
import torch.nn.functional as F
from torch.utils.data import Dataset, DataLoader
from sklearn.model_selection import train_test_split
from preproc import *

def load_data(file_path):
    # Load the workbook
    workbook = openpyxl.load_workbook(file_path)

    # Select the active sheet or specify the sheet name
    sheet = workbook.active
    # Or you can select a specific sheet by name: sheet = workbook['SheetName']

    data = {}

    # Iterate through rows
    for column in sheet.iter_cols(values_only=True):
        id = column[-1]
        if id is None:
            break
        id = int(column[-1])
        row_data = []
        for cell in column:
            if cell == None:
                break
            row_data.append(int(cell))
        if len(row_data) != 0:
            if id not in data:
                data[id] = []
            data[id].append(list(reversed(row_data)))

    # Close the workbook when done
    workbook.close()

    return data

def gen_cycles(data):
    cycle_data = {}
    for key in data:
        for sig in data[key]:
            if key not in cycle_data:
                cycle_data[key] = []
            cycle_data[key].append(sig_preproc(sig))
    return cycle_data

def gen_genuine_pairs(data):
    genuine = []
    for id in data:
        genuine_pairs = list(combinations(data[id], 2))
        genuine.extend(genuine_pairs)

    labels = [1] * len(genuine)

    return genuine, labels

def gen_impostor_pairs(data):
    impostors = []
    for id in data:
        for i in range(int(id)+1, len(data)+1):
            impostor_pairs = list(product(data[id], data[i]))
            impostors.extend(impostor_pairs)

    labels = [0] * len(impostors)

    return impostors, labels

def get_dataset(path):
    data = load_data(path)

    cycles = gen_cycles(data)
    data = []
    labels = []

    data, labels = gen_genuine_pairs(cycles)
    impostor_data, i_labels = gen_impostor_pairs(cycles)
    data.extend(impostor_data)
    labels.extend(i_labels)

    
    combined_lists = list(zip(data, labels))
    random.shuffle(combined_lists)
    data, labels = zip(*combined_lists)

    return list(data), list(labels)

def get_full_dataset():
    train_path = 'yynb8t9x3d-2/train8.xlsx'
    test_path = 'yynb8t9x3d-2/test8.xlsx'
    data = []
    labels = []

    data, labels = get_dataset(train_path)
    test_data, test_labels = get_dataset(test_path)
    data.extend(test_data)
    labels.extend(test_labels)

    return data, labels

def pickle_dataset():
    data, labels = get_full_dataset()
    combined_dict = {'data': data, 'labels': labels}

    # Pickle the combined data and save to a file
    with open('yynb8t9x3d-2/dataset.pkl', 'wb') as file:
        pickle.dump(combined_dict, file)

def unpickle_dataset():

    # Load the pickled data from the file
    with open('yynb8t9x3d-2/dataset.pkl', 'rb') as file:
        loaded_data = pickle.load(file)

    # Access the lists from the loaded data
    data = loaded_data['data']
    labels = loaded_data['labels']

    return data, labels


#pickle_dataset()
data, labels = unpickle_dataset()
X_train, X_test, y_train, y_test = train_test_split(data, labels, test_size=0.2, random_state=42)

# Convert data to PyTorch tensors
X_train = torch.Tensor(X_train).cuda()
X_test = torch.Tensor(X_test).cuda()
y_train = torch.Tensor(y_train).cuda()
y_test = torch.Tensor(y_test).cuda()

# Define Siamese CNN architecture
class SiameseCNN(nn.Module):
    def __init__(self):
        super(SiameseCNN, self).__init__()
        self.cnn = nn.Sequential(
            nn.Conv1d(1, 16, kernel_size=3),
            nn.ReLU(inplace=True),
            nn.MaxPool1d(kernel_size=2),
            nn.BatchNorm1d(16),
            
            nn.Conv1d(16, 32, kernel_size=3),
            nn.ReLU(inplace=True),
            nn.MaxPool1d(kernel_size=2),
            nn.BatchNorm1d(32),
            
            nn.Conv1d(32, 64, kernel_size=3),
            nn.ReLU(inplace=True),
            nn.MaxPool1d(kernel_size=2),
            nn.BatchNorm1d(64),

            nn.Flatten()
        )
        self.fc = nn.Sequential(
            nn.Linear(256, 64),
            nn.ReLU(inplace=True),

            nn.Linear(64, 16),
            nn.ReLU(inplace=True),

            nn.Linear(16, 1),
            nn.Sigmoid(),

        )

    def forward_one(self, x):
        return self.cnn(x)

    def forward(self, input1, input2):
        output1 = self.forward_one(input1)
        output2 = self.forward_one(input2)

        subtracted_output = output1 - output2
        output = self.fc(subtracted_output)
        return output


# Instantiate the Siamese CNN model, loss function, and optimizer
model = SiameseCNN().cuda()
criterion = nn.BCELoss().cuda()
optimizer = optim.Adam(model.parameters(), lr=0.0001)

model.load_state_dict(torch.load('yynb8t9x3d-2/model_params.pt'))

# Convert labels to LongTensor for binary classification
y_train = y_train.type(torch.LongTensor).cuda()
y_test = y_test.type(torch.LongTensor).cuda()

# Create DataLoader for batch training
batch_size = 32
train_dataset = torch.utils.data.TensorDataset(X_train, y_train)
train_loader = torch.utils.data.DataLoader(dataset=train_dataset, batch_size=batch_size, shuffle=True)

print(next(model.parameters()).is_cuda)

# Training loop with batch training
""" num_epochs = 40
for epoch in range(num_epochs):
    model.train()
    for inputs, labels in train_loader:
        optimizer.zero_grad()
        output = model(inputs[:, 0, :].unsqueeze(1), inputs[:, 1, :].unsqueeze(1))
        loss = criterion(output.squeeze(), labels.float())
        loss.backward()
        optimizer.step()
    print(f'Epoch [{epoch + 1}/{num_epochs}], Loss: {loss.item():.4f}')
    torch.save(model.state_dict(), 'yynb8t9x3d-2/model_params.pt') """

# Evaluate on the test set
model.eval()
with torch.no_grad():
    correct = 0
    total = 0
    for i in range(len(X_test)):
        input1, input2, label = X_test[i][0], X_test[i][1], y_test[i]
        output = model(input1.unsqueeze(0).unsqueeze(0), input2.unsqueeze(0).unsqueeze(0))
        prediction = 1 if output > 0.5 else 0  # Adjust threshold as needed
        total += 1
        correct += (prediction == label.item())

accuracy = correct / total
print(f'Test Accuracy: {accuracy * 100:.2f}%')


