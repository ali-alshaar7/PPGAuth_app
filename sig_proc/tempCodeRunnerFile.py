# Training loop with batch training
num_epochs = 40
for epoch in range(num_epochs):
    model.train()
    for inputs, labels in train_loader:
        optimizer.zero_grad()
        output = model(inputs[:, 0, :].unsqueeze(1), inputs[:, 1, :].unsqueeze(1))
        loss = criterion(output.squeeze(), labels.float())
        loss.backward()
        optimizer.step()
    print(f'Epoch [{epoch + 1}/{num_epochs}], Loss: {loss.item():.4f}')
    torch.save(model.state_dict(), 'yynb8t9x3d-2/model_params.pt')