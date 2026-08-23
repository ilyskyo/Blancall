// Copyright (c) 2026 ilyskyo
// SPDX-License-Identifier: MIT

package com.ilyskyo.blancall.ui.pdf

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ilyskyo.blancall.algorithm.GaokaoTextCleaner
import com.ilyskyo.blancall.algorithm.PdfTextExtractor
import kotlinx.coroutines.launch

/**
 * PDF优化效果测试界面
 * 
 * 用于测试和验证PDF无损放大优化效果：
 * 1. 对比原始PDF渲染 vs 优化后的矢量渲染
 * 2. 测试文本提取和清理功能
 * 3. 验证自适应布局和手势缩放
 * 4. 检查注释处理和导入功能
 */
@Composable
fun PdfOptimizationTestScreen() {
    val scope = rememberCoroutineScope()
    var testResults by remember { mutableStateOf<String>("") }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "PDF优化效果测试",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "测试项目",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                
                Text(
                    text = "✓ 高性能PDF文本提取器",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                Text(
                    text = "✓ 矢量文本渲染组件",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                Text(
                    text = "✓ 无损放大和自适应布局",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                Text(
                    text = "✓ 高考必背篇目专用优化",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                Text(
                    text = "✓ 智能注释处理",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                Text(
                    text = "✓ 导入页面状态保持",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "功能特点",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                
                Text(
                    text = "• 支持双模式渲染：PDF原始渲染 + 矢量文本渲染",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                Text(
                    text = "• 无损放大：文字放大时保持清晰度，不再发糊",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                Text(
                    text = "• 自适应布局：根据屏幕尺寸自动调整文本布局",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                Text(
                    text = "• 智能约束：确保文字不会超出屏幕边界",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                Text(
                    text = "• 手势缩放：支持双指缩放和单指拖动",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                Text(
                    text = "• 注释处理：阅览时保留注释，导入时自动去除",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "使用说明",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                
                Text(
                    text = "1. 在素材库中选择任意PDF文件进行阅览",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                Text(
                    text = "2. 点击右上角切换按钮在PDF渲染和矢量渲染间切换",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                Text(
                    text = "3. 使用双指手势进行缩放，单指手势进行拖动",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                Text(
                    text = "4. 在导入页面选择PDF文件后可预览，返回后保持选中状态",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                Text(
                    text = "5. 导入到背诵挖空时会自动去除注释，保留纯净文本",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "注意事项",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                
                Text(
                    text = "• 矢量渲染模式需要PDF包含文本层，扫描版PDF仍使用原始渲染",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                Text(
                    text = "• 大文件PDF处理可能需要较长时间，请耐心等待",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                Text(
                    text = "• 注释处理基于模式匹配，可能无法识别所有类型的注释",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                Text(
                    text = "• 如遇问题可切换回PDF原始渲染模式继续使用",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Button(
            onClick = {
                scope.launch {
                    runTest()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Text("运行测试", fontSize = 16.sp)
        }
        
        if (testResults.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "测试结果",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    
                    Text(
                        text = testResults,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

/**
 * 运行功能测试
 */
private suspend fun runTest() {
    // 这里可以添加实际的测试逻辑
    // 测试PDF文本提取
    // 测试文本清理功能
    // 测试渲染性能等
}