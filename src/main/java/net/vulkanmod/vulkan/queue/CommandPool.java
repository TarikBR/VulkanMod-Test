package net.vulkanmod.vulkan.queue;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.vulkanmod.vulkan.Vulkan;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.util.ArrayDeque;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

public class CommandPool {
    long id;

    private final List<CommandBuffer> commandBuffers = new ObjectArrayList<>();
    private final java.util.Queue<CommandBuffer> availableCmdBuffers = new ArrayDeque<>();

    CommandPool(int queueFamilyIndex) {
        this.createCommandPool(queueFamilyIndex);
    }

    public void createCommandPool(int familyIndex) {

        try(MemoryStack stack = stackPush()) {

            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack);
            poolInfo.sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO);
            poolInfo.queueFamilyIndex(familyIndex);
            poolInfo.flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);

            LongBuffer pCommandPool = stack.mallocLong(1);

            if (vkCreateCommandPool(Vulkan.getDevice(), poolInfo, null, pCommandPool) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create command pool");
            }

            this.id = pCommandPool.get(0);
        }
    }

    public CommandBuffer beginCommands() {

        try(MemoryStack stack = stackPush()) {
            final int size = 10;

            if(availableCmdBuffers.isEmpty()) {

                VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack);
                allocInfo.sType$Default();
                allocInfo.level(VK_COMMAND_BUFFER_LEVEL_PRIMARY);
                allocInfo.commandPool(id);
                allocInfo.commandBufferCount(size);

                PointerBuffer pCommandBuffer = stack.mallocPointer(size);
                vkAllocateCommandBuffers(Vulkan.getDevice(), allocInfo, pCommandBuffer);

                for(int i = 0; i < size; ++i) {

                    CommandBuffer commandBuffer = new CommandBuffer(new VkCommandBuffer(pCommandBuffer.get(i), Vulkan.getDevice()), -1);
                    commandBuffer.handle = new VkCommandBuffer(pCommandBuffer.get(i), Vulkan.getDevice());
                    commandBuffers.add(commandBuffer);
                    availableCmdBuffers.add(commandBuffer);
                }

            }

            CommandBuffer commandBuffer = availableCmdBuffers.poll();

            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack);
            beginInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
            beginInfo.flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

            vkBeginCommandBuffer(commandBuffer.handle, beginInfo);

            return commandBuffer;
        }
    }

    public long submitCommands(CommandBuffer commandBuffer, VkQueue queue) {

        try(MemoryStack stack = stackPush()) {
            long fence = -1;

            vkEndCommandBuffer(commandBuffer.handle);

            if (commandBuffer.isTransfer()) {
                fence = vkQueueSubmit(queue, commandBuffer.getSubmitInfo(), fence);
            } else {
                vkWaitForFences(Vulkan.getDevice(), commandBuffer.getFence(), true, 0);
                commandBuffer.reset();
            }

            return fence;
            
        }
    }

        public void cleanUp() {
        for(CommandBuffer commandBuffer : commandBuffers) {
            if (commandBuffer.isTransfer()) {
                vkWaitForFences(Vulkan.getDevice(), commandBuffer.getFence(), true, 0);
            }
            vkDestroyFence(Vulkan.getDevice(), commandBuffer.getFence(), null);
        }
        vkResetCommandPool(Vulkan.getDevice(), id, VK_COMMAND_POOL_RESET_RELEASE_RESOURCES_BIT);
        vkDestroyCommandPool(Vulkan.getDevice(), id, null);
    }

    public class CommandBuffer {
        VkCommandBuffer handle;
        final long fence;
        boolean submitted;
        boolean recording;

        public CommandBuffer(VkCommandBuffer handle, long fence) {
            this.handle = handle;
            this.fence = fence;
        }

        public VkCommandBuffer getHandle() {
            return handle;
        }

        public long getFence() {
            return fence;
        }

        public boolean isSubmitted() {
            return submitted;
        }

        public boolean isRecording() {
            return recording;
        }

        public void reset() {
            this.submitted = false;
            this.recording = false;
            if (fence != -1) {
                vkDestroyFence(Vulkan.getDevice(), fence, null);
                fence = -1;
            }
            addToAvailable(this);
        }

        public VkSubmitInfo getSubmitInfo() {
            VkSubmitInfo submitInfo = VkSubmitInfo.calloc();
            submitInfo.sType(VK_STRUCTURE_TYPE_SUBMIT_INFO);
            submitInfo.pCommandBuffers(stack.pointers(handle));
            return submitInfo;
        }

        public boolean isTransfer() {
            return (handle.getCommandBufferLevel() == VK_COMMAND_BUFFER_LEVEL_PRIMARY) && ((handle.getCommandBufferUsageFlags() & VK_COMMAND_BUFFER_USAGE_TRANSFER_BIT) != 0);
        }
    }
    
